package bj.phonepilote.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import bj.phonepilote.app.R
import bj.phonepilote.app.admin.Locator
import bj.phonepilote.app.admin.SmsCommands
import bj.phonepilote.app.data.Repo
import java.util.Locale

/** Réponse à « PP LOCATE <code> » : cherche la position puis la renvoie par SMS (sans internet). */
class SmsLocate(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val to = inputData.getString(KEY_TO) ?: return Result.success()
        val subId = inputData.getInt(KEY_SUB, -1)
        val fix = Locator.current(applicationContext, budgetMs = 30_000)
        val loc = fix.location
        val text = if (loc != null) {
            val acc = if (loc.hasAccuracy()) " (+-${loc.accuracy.toInt()} m)" else ""
            val age = fix.ageSeconds?.takeIf { it > 120 }?.let { " il y a ${it / 60} min" }.orEmpty()
            "PhonePilote: position https://maps.google.com/?q=" +
                String.format(Locale.US, "%.5f,%.5f", loc.latitude, loc.longitude) + acc + age
        } else {
            "PhonePilote: position indisponible. " + ascii(fix.error ?: "")
        }
        val err = SmsCommands.reply(applicationContext, to, text, subId)
        SmsCommands.recordEvent(
            applicationContext,
            (if (loc != null) "LOCATE : position trouvée" else "LOCATE : ${fix.error}") +
                (err?.let { ", réponse SMS échouée ($it)" } ?: ", réponse envoyée"),
        )
        // Si internet est disponible : la position rejoint l'historique et la trace remonte sur /admin.
        if (loc != null) Repo.reportSmsLocation(loc) else Repo.syncNow()
        return Result.success()
    }

    /** Android < 12 exécute une tâche prioritaire comme service de premier plan : notification requise. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Commandes PhonePilote", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("PhonePilote")
            .setContentText("Commande en cours")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        private const val KEY_TO = "to"
        private const val KEY_SUB = "sub"
        private const val CHANNEL = "commands"
        private const val NOTIF_ID = 42

        private fun ascii(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^\\x20-\\x7E]"), "")

        fun enqueue(ctx: Context, to: String, subId: Int) {
            val req = OneTimeWorkRequestBuilder<SmsLocate>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_TO to to, KEY_SUB to subId))
                .build()
            runCatching { WorkManager.getInstance(ctx).enqueue(req) }
        }
    }
}
