package bj.phonepilote.app.push

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import bj.phonepilote.app.admin.SmsCommands
import bj.phonepilote.app.data.Repo
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Résultat réel de l'envoi d'une réponse SMS, renvoyé par l'opérateur quelques secondes après.
 * Sans lui, un envoi refusé (crédit épuisé, pas de réseau) passerait pour réussi.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: return
        val outcome = when (resultCode) {
            Activity.RESULT_OK -> "réponse envoyée"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "réponse non envoyée : pas de réseau"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "réponse non envoyée : mode avion"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "réponse refusée par l'opérateur (crédit SMS épuisé ?)"
            else -> "réponse non envoyée (code $resultCode)"
        }
        SmsCommands.recordEvent(context, "$label, $outcome")
        val pending = goAsync()
        Repo.scope.launch {
            try { withTimeoutOrNull(8_000) { Repo.syncNow() } } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_LABEL = "label"
    }
}
