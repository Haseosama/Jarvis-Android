package com.jarvis.android.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.android.R
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.flow.MutableStateFlow

internal const val ACTION_UPDATE_INSTALL = "com.jarvis.android.UPDATE_INSTALL"
private const val CONFIRM_CHANNEL = "jarvis_update"
private const val CONFIRM_NOTIFICATION = 7500

/** What the installer last said about the update, for the settings card. */
internal object UpdateInstall {
    val status = MutableStateFlow<String?>(null)
}

/** In plain words, why Android's installer refused ([status] is a `PackageInstaller.STATUS_*`; [detail] is its own message). */
internal fun installFailureMessage(status: Int, detail: String?): String = when (status) {
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
        tr("Android refuse cette mise à jour : l’APK n’est pas signé avec la même clé que l’application installée (ou c’est une version plus ancienne). Il faut alors désinstaller puis installer la nouvelle version, ce qui efface les réglages.")
    PackageInstaller.STATUS_FAILURE_STORAGE -> tr("Pas assez de place sur le téléphone pour installer la mise à jour.")
    PackageInstaller.STATUS_FAILURE_BLOCKED ->
        tr("Android a bloqué l’installation (une protection du téléphone ou l’autorisation « installer des applications » de Jarvis).")
    PackageInstaller.STATUS_FAILURE_ABORTED -> tr("Installation annulée.")
    PackageInstaller.STATUS_FAILURE_INVALID -> tr("Le fichier téléchargé est invalide pour Android.")
    PackageInstaller.STATUS_FAILURE_CONFLICT -> tr("Android signale un conflit avec l’application installée.")
    else -> trf("L’installation a échoué ({0}).", detail?.takeIf { it.isNotBlank() } ?: status.toString())
}

/**
 * Receives the outcome of a `PackageInstaller` session. The system first asks for the user's confirmation through an activity it hands
 * back here; every other outcome is written to [UpdateInstall.status] and to the log, so a refusal is never silent.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("AppUpdate", "install status=$status detail=$detail")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    UpdateInstall.status.value = installFailureMessage(PackageInstaller.STATUS_FAILURE, detail)
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                offerConfirmation(context, confirm)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w("AppUpdate", "cannot open the confirmation", e)
                }
                UpdateInstall.status.value = tr("Confirmez l’installation dans la fenêtre d’Android (ou dans la notification, si la fenêtre ne s’ouvre pas).")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                clearConfirmation(context)
                UpdateInstall.status.value = tr("Mise à jour installée.")
            }
            else -> {
                clearConfirmation(context)
                UpdateInstall.status.value = installFailureMessage(status, detail)
            }
        }
    }

    private fun offerConfirmation(context: Context, confirm: Intent) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CONFIRM_CHANNEL, tr("Mise à jour de Jarvis"), NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(context, CONFIRM_NOTIFICATION, Intent(confirm), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(
                CONFIRM_NOTIFICATION,
                NotificationCompat.Builder(context, CONFIRM_CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(tr("Mise à jour prête à installer"))
                    .setContentText(tr("Touchez ici pour confirmer l’installation."))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        } catch (_: SecurityException) {
            // no permission to notify: the window is still opened directly
        }
    }

    private fun clearConfirmation(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CONFIRM_NOTIFICATION)
    }
}
