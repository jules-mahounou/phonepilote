package bj.phonepilote.app.push

import bj.phonepilote.app.admin.Protection
import bj.phonepilote.app.data.Repo
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Commandes envoyées par la plateforme (messages FCM « data », priorité haute) :
 *  - ping : le téléphone renvoie son état (sert à vérifier qu'il répond) ;
 *  - lock : verrouillage immédiat de l'écran.
 * La localisation et l'écran « appeler le propriétaire » arrivent à l'étape suivante.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Repo.fcmToken = token
        Repo.sync()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["cmd"]) {
            "ping" -> Repo.sync()
            "lock" -> {
                Protection.lockNow(this)
                Repo.sync()
            }
        }
    }
}
