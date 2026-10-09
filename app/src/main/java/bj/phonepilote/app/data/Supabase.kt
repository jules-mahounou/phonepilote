package bj.phonepilote.app.data

import bj.phonepilote.app.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accès Supabase en REST pur (GoTrue + PostgREST), comme dans xyd.
 * Les comptes sont « numéro de téléphone + mot de passe » : le numéro est converti en adresse
 * technique (<numéro>@<ACCOUNT_DOMAIN>) jamais utilisée pour envoyer d'email.
 */
object Supabase {
    val url: String = BuildConfig.SUPABASE_URL
    private val key: String = BuildConfig.SUPABASE_KEY
    val configured: Boolean get() = url.startsWith("http") && key.isNotBlank()

    private val anon get() = mapOf("apikey" to key)
    private val refreshLock = Mutex()

    private fun emailOf(phone: String) = "$phone@${BuildConfig.ACCOUNT_DOMAIN}"

    // ---------- Auth ----------

    suspend fun signIn(phone: String, password: String): Session {
        val body = JSONObject().put("email", emailOf(phone)).put("password", password).toString()
        val r = Http.request("$url/auth/v1/token?grant_type=password", "POST", anon, body)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return parseSession(r.body)
    }

    suspend fun signUp(phone: String, password: String, name: String): Session {
        val body = JSONObject().put("email", emailOf(phone)).put("password", password)
            .put("data", JSONObject().put("name", name).put("phone", phone)).toString()
        val r = Http.request("$url/auth/v1/signup", "POST", anon, body)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        if (!JSONObject(r.body).has("access_token")) {
            throw Http.ApiException(0, "Compte créé, mais « Confirm email » est actif dans Supabase : désactivez-le.")
        }
        return parseSession(r.body)
    }

    suspend fun signOut(s: Session) {
        runCatching {
            Http.request("$url/auth/v1/logout", "POST", anon + ("Authorization" to "Bearer ${s.accessToken}"), "{}")
        }
    }

    private suspend fun refresh(s: Session): Session {
        val body = JSONObject().put("refresh_token", s.refreshToken).toString()
        val r = Http.request("$url/auth/v1/token?grant_type=refresh_token", "POST", anon, body)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return parseSession(r.body)
    }

    private fun parseSession(json: String): Session {
        val o = JSONObject(json)
        val user = o.getJSONObject("user")
        val expiresAt = if (o.has("expires_at")) o.getLong("expires_at") * 1000
        else System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
        val meta = user.optJSONObject("user_metadata")
        val phone = meta?.optString("phone")?.ifBlank { null } ?: user.optString("email").substringBefore('@')
        return Session(
            accessToken = o.getString("access_token"),
            refreshToken = o.getString("refresh_token"),
            expiresAt = expiresAt,
            userId = user.getString("id"),
            phone = phone,
            name = meta?.optString("name").orEmpty(),
        )
    }

    /** Jeton valide (rafraîchi si nécessaire). Déconnecte si le refresh token est révoqué. */
    private suspend fun accessToken(forceRefresh: Boolean = false): String {
        return refreshLock.withLock {
            val s = Repo.session.value ?: throw Http.ApiException(401, "Non connecté")
            if (!forceRefresh && s.expiresAt - 60_000 > System.currentTimeMillis()) return@withLock s.accessToken
            try {
                val n = refresh(s)
                Repo.saveSession(n)
                n.accessToken
            } catch (e: Http.ApiException) {
                if (e.code in 400..499) Repo.saveSession(null)
                throw e
            }
        }
    }

    // ---------- Requêtes ----------

    private suspend fun authed(path: String, method: String, body: String?, headers: Map<String, String>): Http.Response {
        var token = accessToken()
        var r = Http.request("$url$path", method, anon + headers + ("Authorization" to "Bearer $token"), body)
        if (r.code == 401) {
            token = accessToken(forceRefresh = true)
            r = Http.request("$url$path", method, anon + headers + ("Authorization" to "Bearer $token"), body)
        }
        return r
    }

    private suspend fun authedOk(path: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val r = authed(path, method, body, headers)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return r.body
    }

    private suspend fun public(path: String): String {
        val r = Http.request("$url$path", "GET", anon)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return r.body
    }

    // ---------- Données ----------

    /** Crée ou met à jour la fiche de ce téléphone (clé : identifiant d'installation). */
    suspend fun upsertDevice(device: JSONObject) {
        authedOk(
            "/rest/v1/devices?on_conflict=id", "POST", JSONArray().put(device).toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    suspend fun latestVersion(): AppVersion? {
        val arr = JSONArray(public("/rest/v1/app_versions?select=*&order=version_code.desc&limit=1"))
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return AppVersion(
            versionCode = o.getInt("version_code"),
            versionName = o.optString("version_name"),
            minVersionCode = o.optInt("min_version_code"),
            apkUrl = o.optString("apk_url"),
            changelog = if (o.isNull("changelog")) "" else o.optString("changelog"),
        )
    }
}
