package bj.phonepilote.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import bj.phonepilote.app.R
import bj.phonepilote.app.data.Repo

/** Réception des changements d'état du Device Admin : chaque changement est remonté au serveur. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Repo.sync()
    }

    // Message affiché par Android avant la désactivation : dernier rempart en mode Device Admin.
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        Repo.sync()
    }
}
