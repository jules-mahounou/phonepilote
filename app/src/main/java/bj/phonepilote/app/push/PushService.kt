package bj.phonepilote.app.push

import bj.phonepilote.app.admin.Protection
import bj.phonepilote.app.data.Repo
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Commandes envoyées par la plateforme (messages FCM « data », priorité haute) :
 *  - ping : le téléphone renvoie son état (sert à vérifier qu'il répond) ;
 *  - lock : verrouillage immédiat de l'écran ;
 *  - locate : le téléphone renvoie sa position.
 * Le message ne fait que réveiller l'app : la commande elle-même est lue dans la base.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Repo.fcmToken = token
        Repo.sync()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Verrouillage immédiat pour la réactivité ; le reste (statut, localisation) passe par le
        // même exécuteur que le repli sans push, ce qui met aussi à jour la commande côté serveur.
        if (message.data["cmd"] == "lock") Protection.lockNow(this)
        // onMessageReceived tourne sur un thread de fond : on exécute ici, avant qu'Android ne coupe le service.
        runBlocking { withTimeoutOrNull(19_000) { Repo.runPendingCommands() } }
    }
}
