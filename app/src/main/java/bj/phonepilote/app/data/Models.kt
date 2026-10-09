package bj.phonepilote.app.data

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val phone: String,
    val name: String,
)

/** Informations affichées à celui qui trouve le téléphone, et transmises à la plateforme. */
data class Owner(
    val name: String,
    val emergencyPhone: String,
    val imei: String,
)

data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int,
    val apkUrl: String,
    val changelog: String,
)

/** Numéros béninois : saisie libre (« 01 97 00 00 00 », « +229… », « 0022997… ») → « 2290197000000 ». */
object Phone {
    fun normalize(input: String): String? {
        var d = input.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        if (!d.startsWith("229") || d.length <= 10) d = "229$d"
        return d.takeIf { it.length in 11..13 }
    }

    /** Affichage lisible : « +229 01 97 00 00 00 ». */
    fun pretty(normalized: String): String {
        if (!normalized.startsWith("229")) return normalized
        val local = normalized.drop(3)
        return "+229 " + local.chunked(2).joinToString(" ")
    }

    fun isValidImei(imei: String): Boolean {
        if (imei.length != 15 || !imei.all { it.isDigit() }) return false
        // Clé de Luhn : évite les fautes de frappe.
        val sum = imei.mapIndexed { i, c ->
            val n = c - '0'
            if (i % 2 == 1) (n * 2).let { if (it > 9) it - 9 else it } else n
        }.sum()
        return sum % 10 == 0
    }
}
