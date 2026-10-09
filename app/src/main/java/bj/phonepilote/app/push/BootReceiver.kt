package bj.phonepilote.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import bj.phonepilote.app.data.Repo

/** Redémarrage ou mise à jour de l'app : le téléphone se signale de nouveau au serveur. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.sync()
    }
}
