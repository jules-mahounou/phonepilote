package bj.phonepilote.app.admin

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** État des réglages nécessaires à la protection, et intents pour les corriger. */
object Protection {
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isAdminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Android 10+ : la localisation en arrière-plan est une permission séparée (« Toujours autoriser »). */
    fun hasBackgroundLocation(ctx: Context) =
        Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /**
     * Code PIN, schéma ou mot de passe actif. Sans lui, « verrouiller » ne fait qu'éteindre l'écran :
     * n'importe qui peut le rallumer. Android interdit à l'app de poser ce code elle-même.
     */
    fun hasScreenLock(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Interrupteur « Localisation » du téléphone (différent de la permission accordée à l'app). */
    fun isLocationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    }

    /** Écran système de création du code de verrouillage (ne nécessite pas l'administrateur). */
    fun screenLockIntent() = Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)

    fun locationSettingsIntent() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun hasNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun ignoresBattery(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Écran système « Activer l'administrateur de l'appareil ». */
    fun adminIntent(ctx: Context) = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Activez la protection pour pouvoir verrouiller ce téléphone à distance s'il est perdu ou volé. " +
                "PhonePilote n'efface jamais vos données.",
        )

    @android.annotation.SuppressLint("BatteryLife")
    fun batteryIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun appSettingsIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    /** Verrouille l'écran immédiatement (Device Admin, politique force-lock). */
    fun lockNow(ctx: Context): Boolean =
        isAdminActive(ctx) && runCatching { dpm(ctx).lockNow() }.isSuccess

    /**
     * Tecno, Infinix et Itel (HiOS / XOS), Xiaomi, Oppo, Vivo… tuent les apps en arrière-plan.
     * Ces écrans constructeur (« démarrage automatique ») n'existent pas partout : on essaie dans l'ordre.
     */
    fun autostartIntents(): List<Intent> {
        fun i(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))
        return when (Build.MANUFACTURER.lowercase()) {
            "tecno", "infinix", "itel" -> listOf(
                i("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
                i("com.transsion.phonemanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity"),
            )
            "xiaomi", "redmi", "poco" -> listOf(
                i("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            "oppo", "realme", "oneplus" -> listOf(
                i("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                i("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            )
            "vivo" -> listOf(
                i("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            "huawei", "honor" -> listOf(
                i("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )
            "samsung" -> listOf(
                i("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            )
            else -> emptyList()
        }
    }

    /** Ouvre le premier écran constructeur disponible, sinon la fiche de l'app. */
    fun openAutostart(ctx: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        for (intent in autostartIntents()) {
            if (runCatching { ctx.startActivity(intent.addFlags(flags)) }.isSuccess) return
        }
        runCatching { ctx.startActivity(appSettingsIntent(ctx).addFlags(flags)) }
    }

    val hasAutostartScreen get() = autostartIntents().isNotEmpty()
}
