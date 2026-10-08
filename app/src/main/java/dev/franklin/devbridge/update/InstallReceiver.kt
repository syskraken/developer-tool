package dev.franklin.devbridge.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives the outcome of a [PackageInstaller] session started by [Updater]. */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants to show its own install prompt; it hands us
                // the Intent to launch rather than showing it itself.
                val confirm = confirmationIntent(intent)
                if (confirm == null) {
                    Updater.onInstallResult(false, "System did not return an install prompt")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Updater.onInstallResult(false, e.message)
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                Updater.onInstallResult(true, null)

            else ->
                Updater.onInstallResult(
                    false,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
