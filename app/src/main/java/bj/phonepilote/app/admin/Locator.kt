package bj.phonepilote.app.admin

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Position du téléphone, via le LocationManager du framework (aucune dépendance Play Services).
 * Ordre : fused (Android 12+) → réseau → GPS, puis repli sur la dernière position connue.
 */
object Locator {
    /** [location] non nulle = succès ; sinon [error] dit précisément ce qui bloque. */
    class Fix(val location: Location?, val error: String?, val provider: String? = null) {
        /** Âge de la position en secondes (une position « dernière connue » peut être ancienne). */
        val ageSeconds: Long?
            get() = location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000 }
    }

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, budgetMs: Long = 25_000): Fix {
        if (!Protection.hasLocation(ctx)) return Fix(null, "Permission de localisation refusée à PhonePilote")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Fix(null, "Service de localisation absent")
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            return Fix(null, "Localisation désactivée dans les réglages du téléphone")
        }

        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val providers = candidates.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return Fix(null, "Aucun fournisseur de position actif (GPS et réseau coupés)")

        // Android 11+ : position fraîche. Réseau / fused : 8 s max ; le GPS prend le temps restant.
        if (Build.VERSION.SDK_INT >= 30) {
            val deadline = SystemClock.elapsedRealtime() + budgetMs
            for (p in providers) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left < 1_000) break
                val wait = if (p == LocationManager.GPS_PROVIDER) left else minOf(left, 8_000)
                val loc = withTimeoutOrNull(wait) { singleUpdate(lm, p, ctx) }
                if (loc != null) return Fix(loc, null, p)
            }
        }

        // Repli : la plus récente des dernières positions connues (y compris celles d'autres apps).
        val last = (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { p to it } }
            .maxByOrNull { it.second.elapsedRealtimeNanos }
        return if (last != null) Fix(last.second, null, "${last.first} (dernière connue)")
        else Fix(null, "Aucune position obtenue en ${budgetMs / 1000} s (pas de signal GPS ni réseau ?)")
    }

    @SuppressLint("MissingPermission")
    private suspend fun singleUpdate(lm: LocationManager, provider: String, ctx: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { runCatching { signal.cancel() } }
            runCatching {
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(ctx)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
}
