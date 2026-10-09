package bj.phonepilote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import bj.phonepilote.app.BuildConfig
import bj.phonepilote.app.admin.Protection
import bj.phonepilote.app.push.Push
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** État global de l'app (process-scoped). Pas de DI, pas de ViewModel : tout tient ici (comme xyd). */
object Repo {
    lateinit var ctx: Context
        private set
    private lateinit var prefs: SharedPreferences
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val syncLock = Mutex()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private val _owner = MutableStateFlow<Owner?>(null)
    val owner: StateFlow<Owner?> = _owner

    /** null = jamais synchronisé ; sinon instant (ms) de la dernière synchro réussie. */
    val lastSync = MutableStateFlow<Long?>(null)
    val syncError = MutableStateFlow<String?>(null)

    /** Version distante plus récente que l'APK installé (null = à jour). */
    val update = MutableStateFlow<AppVersion?>(null)

    /** Identifiant d'installation : clé de la fiche « devices » côté serveur. */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("phonepilote", Context.MODE_PRIVATE)
        _session.value = prefs.getString("session", null)?.let { runCatching { sessionFromJson(it) }.getOrNull() }
        _owner.value = prefs.getString("owner", null)?.let { runCatching { ownerFromJson(it) }.getOrNull() }
        lastSync.value = prefs.getLong("last_sync", 0L).takeIf { it > 0 }
        autostartAck.value = prefs.getBoolean("autostart_ack", false)
        demoMode.value = prefs.getBoolean("demo", false)
    }

    // ---------- Session ----------

    fun saveSession(s: Session?) {
        _session.value = s
        prefs.edit().apply {
            if (s == null) remove("session") else putString("session", sessionToJson(s))
        }.apply()
    }

    suspend fun signIn(phone: String, password: String) {
        saveSession(Supabase.signIn(phone, password))
        sync()
    }

    suspend fun signUp(phone: String, password: String, name: String) {
        saveSession(Supabase.signUp(phone, password, name.trim()))
        sync()
    }

    private fun sessionToJson(s: Session) = JSONObject()
        .put("a", s.accessToken).put("r", s.refreshToken).put("e", s.expiresAt)
        .put("u", s.userId).put("p", s.phone).put("n", s.name).toString()

    private fun sessionFromJson(j: String) = JSONObject(j).let {
        Session(it.getString("a"), it.getString("r"), it.getLong("e"), it.getString("u"), it.getString("p"), it.optString("n"))
    }

    // ---------- Propriétaire ----------

    fun saveOwner(o: Owner) {
        _owner.value = o
        prefs.edit().putString("owner", ownerToJson(o)).apply()
        sync()
    }

    private fun ownerToJson(o: Owner) = JSONObject()
        .put("name", o.name).put("phone", o.emergencyPhone).put("imei", o.imei).toString()

    private fun ownerFromJson(j: String) = JSONObject(j).let {
        Owner(it.getString("name"), it.getString("phone"), it.optString("imei"))
    }

    // ---------- Réglages non vérifiables par l'app ----------

    /** Démarrage automatique (écran constructeur) : Android ne permet pas de lire ce réglage, l'utilisateur le confirme. */
    val autostartAck = MutableStateFlow(false)

    fun ackAutostart() {
        autostartAck.value = true
        prefs.edit().putBoolean("autostart_ack", true).apply()
    }

    /** Mode démo : Supabase pas encore configuré, on autorise l'onboarding sans compte. */
    val demoMode = MutableStateFlow(false)

    fun enterDemo() {
        demoMode.value = true
        prefs.edit().putBoolean("demo", true).apply()
    }

    // ---------- Push ----------

    var fcmToken: String?
        get() = prefs.getString("fcm_token", null)
        set(v) = prefs.edit().putString("fcm_token", v).apply()

    // ---------- Synchronisation avec la plateforme ----------

    /**
     * Envoie l'état du téléphone (modèle, protection active, jeton push, propriétaire) au serveur.
     * Appelée au démarrage, à chaque changement local, au reboot et à la réception d'un nouveau jeton FCM.
     */
    fun sync() {
        if (!Supabase.configured || _session.value == null) return
        scope.launch {
            syncLock.withLock {
                try {
                    if (fcmToken == null) fcmToken = Push.token()
                    Supabase.upsertDevice(deviceJson())
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong("last_sync", now).apply()
                    lastSync.value = now
                    syncError.value = null
                } catch (e: Exception) {
                    syncError.value = Http.friendly(e)
                }
            }
        }
    }

    private fun deviceJson(): JSONObject {
        val o = _owner.value
        return JSONObject()
            .put("id", deviceId)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("app_version", BuildConfig.VERSION_CODE)
            .put("fcm_token", fcmToken ?: JSONObject.NULL)
            .put("admin_active", Protection.isAdminActive(ctx))
            .put("location_ok", Protection.hasLocation(ctx) && Protection.hasBackgroundLocation(ctx))
            .put("battery_ok", Protection.ignoresBattery(ctx))
            .put("owner_name", o?.name ?: JSONObject.NULL)
            .put("emergency_phone", o?.emergencyPhone ?: JSONObject.NULL)
            .put("imei", o?.imei?.ifBlank { null } ?: JSONObject.NULL)
            .put("last_seen", Instant.now().toString())
    }

    // ---------- Commandes à distance (verrouiller / localiser) ----------

    private val handledCommands = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val commandLock = Mutex()

    /**
     * Récupère les commandes en attente et les exécute. Appelée à la réception d'un push
     * et, tant que l'app est au premier plan, toutes les quelques secondes (repli sans push).
     */
    fun pollCommands() {
        if (!Supabase.configured || _session.value == null) return
        scope.launch {
            commandLock.withLock {
                val pending = runCatching { Supabase.pendingCommands(deviceId) }.getOrNull() ?: return@withLock
                for (i in 0 until pending.length()) {
                    val c = pending.getJSONObject(i)
                    val id = c.getString("id")
                    if (!handledCommands.add(id)) continue
                    runCatching { execute(id, c.getString("kind")) }
                }
            }
        }
    }

    private suspend fun execute(id: String, kind: String) {
        var status = "done"
        var result: JSONObject? = null
        when (kind) {
            "ping" -> result = JSONObject().put("ok", true)
            "lock" -> {
                val ok = Protection.lockNow(ctx)
                status = if (ok) "done" else "failed"
                result = JSONObject().put("locked", ok)
            }
            "locate" -> {
                val loc = bj.phonepilote.app.admin.Locator.current(ctx)
                if (loc != null) {
                    runCatching { Supabase.insertLocation(deviceId, loc.latitude, loc.longitude, loc.accuracy.takeIf { loc.hasAccuracy() }) }
                    result = JSONObject().put("lat", loc.latitude).put("lng", loc.longitude)
                } else {
                    status = "failed"
                    result = JSONObject().put("error", "Position indisponible (localisation désactivée ?)")
                }
            }
            "unlock" -> result = JSONObject().put("note", "unlock non applicable en v1")
            else -> { status = "failed"; result = JSONObject().put("error", "Commande inconnue") }
        }
        runCatching { Supabase.finishCommand(id, status, result) }
        sync()
    }

    fun signOut() {
        val s = _session.value ?: return
        saveSession(null)
        scope.launch { Supabase.signOut(s) }
    }

    // ---------- Mise à jour ----------

    suspend fun checkUpdate() {
        if (!Supabase.configured) return
        val v = runCatching { Supabase.latestVersion() }.getOrNull() ?: return
        update.value = if (v.versionCode > BuildConfig.VERSION_CODE && v.apkUrl.isNotBlank()) v else null
    }
}
