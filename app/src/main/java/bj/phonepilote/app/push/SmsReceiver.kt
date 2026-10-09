package bj.phonepilote.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import bj.phonepilote.app.admin.Protection
import bj.phonepilote.app.admin.SmsCommands
import bj.phonepilote.app.data.Repo
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SMS reçus : seuls les messages « PP LOCK <code> » / « PP LOCATE <code> » sont traités, tous les
 * autres SMS sont ignorés (et restent dans la messagerie, Android interdit de les masquer).
 * Chaque commande laisse une trace remontée sur /admin (diagnostic).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        val subId = SmsCommands.subscriptionOf(intent)
        // Un SMS long arrive en plusieurs morceaux : on les rassemble par expéditeur.
        val bySender = messages.filter { it.originatingAddress != null }
            .groupBy { it.originatingAddress!! }
            .mapValues { (_, parts) -> parts.joinToString("") { it.messageBody.orEmpty() } }

        var handled = false
        for ((sender, body) in bySender) {
            val cmd = SmsCommands.parse(body) ?: continue
            handled = true
            val name = cmd.kind.name
            // Garde-fou agréments : rien n'est exécuté hors mode test (et aucune réponse n'est envoyée).
            if (!SmsCommands.remoteAllowed(context)) { SmsCommands.recordEvent(context, "$name ignoré : mode test inactif"); continue }
            if (!SmsCommands.isCodeSet(context)) { SmsCommands.recordEvent(context, "$name ignoré : aucun code"); continue }
            if (!SmsCommands.authorize(context, sender, cmd.code)) {
                SmsCommands.recordEvent(context, "$name refusé : code faux ou numéro bloqué"); continue
            }
            when (cmd.kind) {
                SmsCommands.Kind.LOCK -> {
                    val ok = Protection.lockNow(context)
                    val label = if (ok) "LOCK fait" else "LOCK échoué"
                    val err = SmsCommands.reply(
                        context, sender,
                        if (ok) "PhonePilote: telephone verrouille." else "PhonePilote: echec du verrouillage (protection desactivee).",
                        subId, label,
                    )
                    SmsCommands.recordEvent(context, label + (err?.let { ", réponse SMS échouée ($it)" } ?: ", envoi de la réponse…"))
                }
                // La position peut prendre ~30 s : tâche WorkManager prioritaire plutôt que ce récepteur.
                SmsCommands.Kind.LOCATE -> {
                    SmsCommands.recordEvent(context, "LOCATE reçu, recherche de la position…")
                    SmsLocate.enqueue(context, sender, subId)
                }
            }
        }
        if (!handled) return
        // Remonte la trace sur /admin avant qu'Android n'arrête le processus.
        val pending = goAsync()
        Repo.scope.launch {
            try { withTimeoutOrNull(8_000) { Repo.syncNow() } } finally { pending.finish() }
        }
    }
}
