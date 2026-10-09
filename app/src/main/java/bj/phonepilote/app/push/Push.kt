package bj.phonepilote.app.push

import android.content.Context
import bj.phonepilote.app.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Firebase sert uniquement à recevoir les commandes à distance (FCM).
 * Pas de google-services.json : la config vient des secrets GitHub via BuildConfig.
 */
object Push {
    val configured: Boolean
        get() = BuildConfig.FB_PROJECT_ID.isNotBlank() && BuildConfig.FB_APP_ID.isNotBlank() &&
            BuildConfig.FB_API_KEY.isNotBlank() && BuildConfig.FB_SENDER_ID.isNotBlank()

    fun init(ctx: Context) {
        if (!configured || FirebaseApp.getApps(ctx).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FB_PROJECT_ID)
            .setApplicationId(BuildConfig.FB_APP_ID)
            .setApiKey(BuildConfig.FB_API_KEY)
            .setGcmSenderId(BuildConfig.FB_SENDER_ID)
            .build()
        runCatching { FirebaseApp.initializeApp(ctx, options) }
    }

    /** Jeton FCM de ce téléphone, ou null (Firebase non configuré, pas de Google Play Services, hors ligne). */
    suspend fun token(): String? {
        if (!configured || FirebaseApp.getApps(bj.phonepilote.app.data.Repo.ctx).isEmpty()) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { t -> cont.resume(if (t.isSuccessful) t.result else null) }
        }
    }
}
