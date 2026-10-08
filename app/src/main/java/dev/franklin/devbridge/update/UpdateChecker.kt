package dev.franklin.devbridge.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub whether a newer release has been published. Apps installed
 * outside Play get no automatic updates, so this is how a user finds out.
 */
object UpdateChecker {

    // Tied to the repository name; renaming the repo breaks this URL.
    private const val LATEST_RELEASE = "https://api.github.com/repos/syskraken/developer-tool/releases/latest"

    class Release(
        val version: String,
        val pageUrl: String,
        val apkUrl: String?,
        val sha256Url: String? = null,
    )

    sealed class Result {
        object UpToDate : Result()
        class Available(val release: Release) : Result()

        /** The endpoint 404s until a release is published — drafts do not count. */
        object NoReleases : Result()
        class Failed(val reason: String) : Result()
    }

    /** Blocking network call. */
    fun check(currentVersion: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DevBridge (Android)")
            }
            when (val code = connection.responseCode) {
                404 -> Result.NoReleases
                in 200..299 -> parse(connection.inputStream.bufferedReader().use { it.readText() }, currentVersion)
                403 -> Result.Failed("rate limited by GitHub, try later")
                else -> Result.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parse(body: String, currentVersion: String): Result {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
            ?: return Result.Failed("release has no tag")

        var apkUrl: String? = null
        var sha256Url: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                when {
                    // "x.apk.sha256" does not end in ".apk", but test the checksum suffix first anyway.
                    name.endsWith(".sha256", ignoreCase = true) -> sha256Url = url
                    name.endsWith(".apk", ignoreCase = true) && apkUrl == null -> apkUrl = url
                }
            }
        }

        val release = Release(
            version = Versions.normalise(tag),
            pageUrl = json.optString("html_url"),
            apkUrl = apkUrl,
            sha256Url = sha256Url,
        )
        return if (Versions.compare(release.version, currentVersion) > 0) Result.Available(release) else Result.UpToDate
    }
}
