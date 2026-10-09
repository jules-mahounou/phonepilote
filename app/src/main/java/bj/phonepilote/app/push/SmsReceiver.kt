package bj.phonepilote.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import bj.phonepilote.app.admin.Protection
import bj.phonepilote.app.admin.SmsCommands
import bj.phonepilote.app.data.Repo

/**
 * SMS reçus : seuls les messages « PP LOCK <code> » / « PP LOCATE <code> » sont traités, tous les
 * autres SMS sont ignorés (et restent dans la messagerie, Android interdit de les masquer).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        // Un SMS long arrive en plusieurs morceaux : on les rassemble par expéditeur.
        val bySender = messages.filter { it.originatingAddress != null }
            .groupBy { it.originatingAddress!! }
            .mapValues { (_, parts) -> parts.joinToString("") { it.messageBody.orEmpty() } }

        for ((sender, body) in bySender) {
            val cmd = SmsCommands.parse(body) ?: continue
            // Garde-fou agréments : rien n'est exécuté hors mode test (et aucune réponse n'est envoyée).
            if (!SmsCommands.remoteAllowed(context) || !SmsCommands.isCodeSet(context)) continue
            if (!SmsCommands.authorize(context, sender, cmd.code)) continue

            SmsCommands.recordCommand(context, cmd.kind)
            when (cmd.kind) {
                SmsCommands.Kind.LOCK -> {
                    val ok = Protection.lockNow(context)
                    SmsCommands.reply(
                        context, sender,
                        if (ok) "PhonePilote: telephone verrouille." else "PhonePilote: echec du verrouillage (protection desactivee).",
                    )
                }
                // La position peut prendre ~20 s : tâche WorkManager prioritaire plutôt que ce récepteur.
                SmsCommands.Kind.LOCATE -> SmsLocate.enqueue(context, sender)
            }
            Repo.sync()
        }
    }
}
