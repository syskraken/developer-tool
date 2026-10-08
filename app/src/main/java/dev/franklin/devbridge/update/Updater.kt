package dev.franklin.devbridge.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * Uses [PackageInstaller] rather than an ACTION_VIEW intent: no FileProvider is
 * needed, and the session reports back why an install failed instead of failing
 * silently. Android still shows its own confirmation prompt — nothing here can
 * install anything without the user agreeing to it on screen.
 */
object Updater {

    sealed class Status {
        object Idle : Status()
        class Downloading(val percent: Int) : Status()
        object Verifying : Status()
        object Installing : Status()
        object Success : Status()
        class Failed(val reason: String) : Status()
    }

    @Volatile
    var status: Status = Status.Idle
        private set

    private val revision = AtomicLong()

    /** Bumped on every state change so the UI can skip redundant redraws. */
    fun revision(): Long = revision.get()

    private fun set(next: Status) {
        status = next
        revision.incrementAndGet()
    }

    fun reset() = set(Status.Idle)

    /**
     * Below Android 8 the unknown-sources setting was global, so there is
     * nothing per-app to grant.
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /** Blocking: download, verify, then start the install. Run off the main thread. */
    fun downloadAndInstall(context: Context, release: UpdateChecker.Release) {
        val apkUrl = release.apkUrl
        if (apkUrl == null) {
            set(Status.Failed("This release has no APK attached"))
            return
        }

        val target = File(context.cacheDir, "update-${release.version}.apk")
        try {
            set(Status.Downloading(0))
            download(apkUrl, target) { percent -> set(Status.Downloading(percent)) }

            set(Status.Verifying)
            verify(target, release.sha256Url)

            set(Status.Installing)
            startInstall(context, target)
        } catch (e: Exception) {
            target.delete()
            set(Status.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Compares against the published checksum when one exists. Android also
     * refuses any APK whose signing certificate differs from the installed app,
     * so this guards against a corrupted download rather than a forged one.
     */
    private fun verify(apk: File, sha256Url: String?) {
        if (sha256Url == null) return

        val published = try {
            Checksum.parseSha256(fetchText(sha256Url))
        } catch (e: Exception) {
            null
        } ?: return

        val actual = Checksum.sha256(apk)
        if (!actual.equals(published, ignoreCase = true)) {
            throw IOException("Checksum mismatch — the download is corrupt")
        }
    }

    private fun startInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Android 12+ may then skip the confirmation for an update to an app this installer already manages;
        // when it can't, it still asks as before.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite("apk", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            // Must be mutable: the system fills in the status extras before firing it.
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

            val pending = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallReceiver::class.java),
                flags,
            )
            session.commit(pending.intentSender)
        }
    }

    /** Called by [InstallReceiver] once the system reports an outcome. */
    fun onInstallResult(success: Boolean, message: String?) {
        set(if (success) Status.Success else Status.Failed(friendly(message)))
    }

    private fun friendly(message: String?): String = when {
        message == null -> "Install cancelled"
        message.contains("DOWNGRADE", ignoreCase = true) ->
            "This copy is newer than the release (it is probably a test build), so Android won't replace it. " +
                "Uninstall DevBridge to go back to the release."
        message.contains("INCOMPATIBLE", ignoreCase = true) || message.contains("signature", ignoreCase = true) ->
            "This copy was signed with a different key (for example a debug build from CI). " +
                "Uninstall DevBridge, then install the release APK."
        else -> message
    }


    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DevBridge (Android)")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DevBridge (Android)")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
