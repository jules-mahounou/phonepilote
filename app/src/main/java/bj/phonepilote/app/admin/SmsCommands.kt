package bj.phonepilote.app.admin

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Commandes par SMS, sans internet :  « PP LOCK 482193 »  ou  « PP LOCATE 482193 ».
 *
 * - Le code (6 chiffres) est choisi par le propriétaire ; seule son empreinte PBKDF2 (+ sel) est stockée.
 * - Anti-essais : 3 erreurs d'un même numéro → numéro ignoré 1 h ; 10 erreurs en 24 h → tout bloqué 24 h.
 * - Un SMS ne peut que verrouiller ou localiser : jamais désactiver la protection.
 * - Avant les agréments, n'agit que si le téléphone est en mode test (lu sur le serveur à chaque synchro).
 */
object SmsCommands {
    enum class Kind { LOCK, LOCATE }
    class Parsed(val kind: Kind, val code: String)

    private const val ITERATIONS = 10_000
    private const val HOUR = 3_600_000L
    private val PATTERN = Regex("""^\s*PP\s+(LOCK|LOCATE|VERROUILLER|LOCALISER)\s+(\d{6})\s*$""", RegexOption.IGNORE_CASE)

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("pp_sms", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- Code secret

    fun isCodeSet(ctx: Context) = prefs(ctx).contains("hash")

    /** Refuse les codes triviaux (000000, 123456, 987654…). Retourne un message d'erreur ou null. */
    fun validate(code: String): String? = when {
        !Regex("""\d{6}""").matches(code) -> "Le code doit comporter exactement 6 chiffres"
        code.toSet().size == 1 -> "Code trop simple (chiffres identiques)"
        code.zipWithNext { a, b -> b - a }.toSet().let { it == setOf(1) || it == setOf(-1) } -> "Code trop simple (suite de chiffres)"
        else -> null
    }

    fun setCode(ctx: Context, code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(code, salt), Base64.NO_WRAP))
            .remove("fails").remove("blocked_until")
            .apply()
    }

    fun clearCode(ctx: Context) = prefs(ctx).edit().remove("salt").remove("hash").apply()

    private fun verify(ctx: Context, code: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = p.getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(hash(code, salt), expected)
    }

    private fun hash(code: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)).encoded

    // ---------------------------------------------------------------- Autorisation (mode test)

    /** Mis à jour à chaque synchro avec le serveur (devices.test_mode). */
    fun setRemoteAllowed(ctx: Context, allowed: Boolean) = prefs(ctx).edit().putBoolean("allowed", allowed).apply()
    fun remoteAllowed(ctx: Context) = prefs(ctx).getBoolean("allowed", false)

    // ---------------------------------------------------------------- Permissions

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun hasPermissions(ctx: Context) = granted(ctx, Manifest.permission.RECEIVE_SMS) && granted(ctx, Manifest.permission.SEND_SMS)
    val permissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)

    /** Prêt à recevoir des commandes (affiché sur /admin). */
    fun enabled(ctx: Context) = isCodeSet(ctx) && hasPermissions(ctx)

    // ---------------------------------------------------------------- Réception

    fun parse(body: String): Parsed? {
        val m = PATTERN.matchEntire(body) ?: return null
        val kind = when (m.groupValues[1].uppercase()) {
            "LOCK", "VERROUILLER" -> Kind.LOCK
            else -> Kind.LOCATE
        }
        return Parsed(kind, m.groupValues[2])
    }

    /** Fin du blocage global (ms), ou 0. */
    fun blockedUntil(ctx: Context): Long = prefs(ctx).getLong("blocked_until", 0L).takeIf { it > System.currentTimeMillis() } ?: 0L

    /**
     * Vérifie le code en appliquant l'anti-essais. Retourne true si la commande doit être exécutée.
     * Un numéro bloqué ou un blocage global ignore la commande sans même tester le code.
     */
    @Synchronized
    fun authorize(ctx: Context, sender: String, code: String): Boolean {
        val now = System.currentTimeMillis()
        if (blockedUntil(ctx) > 0) return false
        val p = prefs(ctx)
        val fails = JSONArray(p.getString("fails", "[]")) // [[expéditeur, instant], …]
        val recent = (0 until fails.length()).map { fails.getJSONArray(it) }
            .filter { now - it.getLong(1) < 24 * HOUR }
        val key = sender.filter { it.isDigit() }.takeLast(8)
        if (recent.count { it.getString(0) == key && now - it.getLong(1) < HOUR } >= 3) return false

        if (verify(ctx, code)) return true

        val updated = JSONArray().apply {
            recent.forEach { put(it) }
            put(JSONArray().put(key).put(now))
        }
        p.edit().putString("fails", updated.toString()).apply()
        if (updated.length() >= 10) p.edit().putLong("blocked_until", now + 24 * HOUR).apply()
        return false
    }

    // ---------------------------------------------------------------- Journal (affiché sur /admin)

    /** Dernier événement SMS (« LOCATE répondu », « refusé : code faux »…) : sert au diagnostic sur /admin. */
    fun recordEvent(ctx: Context, event: String) = prefs(ctx).edit()
        .putString("last_cmd", event).putLong("last_at", System.currentTimeMillis()).apply()

    fun lastEvent(ctx: Context): Pair<String, Long>? {
        val p = prefs(ctx)
        val cmd = p.getString("last_cmd", null) ?: return null
        return cmd to p.getLong("last_at", 0L)
    }

    // ---------------------------------------------------------------- Réponse

    /** Identifiant de la SIM qui a reçu le SMS (double SIM), ou -1. */
    fun subscriptionOf(intent: android.content.Intent): Int =
        intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))

    /**
     * Répond par la SIM qui a reçu la commande : avec deux SIM et « demander à chaque fois »,
     * la SIM par défaut n'existe pas et Android refuse l'envoi sans erreur visible.
     * Texte en ASCII : un SMS accentué est limité à 70 caractères au lieu de 160.
     * Retourne null si l'envoi est parti, sinon la raison de l'échec.
     */
    fun reply(ctx: Context, to: String, text: String, subId: Int): String? {
        if (!granted(ctx, Manifest.permission.SEND_SMS)) return "permission d'envoi refusée"
        return runCatching {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                val base = ctx.getSystemService(SmsManager::class.java)
                if (subId >= 0) base.createForSubscriptionId(subId) else base
            } else {
                @Suppress("DEPRECATION")
                if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(to, null, sms.divideMessage(text), null, null)
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }
}
