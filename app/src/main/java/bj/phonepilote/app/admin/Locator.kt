package bj.phonepilote.app.admin

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Position du téléphone, via le LocationManager du framework (aucune dépendance Play Services).
 * On tente une position fraîche (GPS/réseau) puis on retombe sur la dernière position connue.
 */
object Locator {
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!Protection.hasLocation(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        // Android 11+ : getCurrentLocation donne une position fraîche sans laisser le GPS allumé.
        if (Build.VERSION.SDK_INT >= 30) {
            for (p in providers) {
                val loc = withTimeoutOrNull(15_000) { singleUpdate(lm, p, ctx) }
                if (loc != null) return loc
            }
        }
        // Repli : la plus récente des dernières positions connues.
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
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
