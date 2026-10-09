package bj.phonepilote.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import bj.phonepilote.app.admin.Protection
import bj.phonepilote.app.data.Phone
import bj.phonepilote.app.data.Repo
import java.text.DateFormat
import java.util.Date

/** Un réglage nécessaire à la protection. */
enum class Step(val title: String, val subtitle: String) {
    ADMIN("Verrouillage à distance", "Administrateur de l'appareil"),
    LOCATION("Localisation", "Position précise"),
    BG_LOCATION("Localisation en arrière-plan", "« Toujours autoriser »"),
    NOTIFS("Notifications", "Alertes de PhonePilote"),
    BATTERY("Batterie sans restriction", "Pour recevoir les commandes"),
    AUTOSTART("Démarrage automatique", "Réglage du constructeur"),
    SYNC("Liaison à la plateforme", "Compte PhonePilote"),
}

fun Step.icon(): ImageVector = when (this) {
    Step.ADMIN -> Icons.Filled.Lock
    Step.LOCATION -> Icons.Filled.LocationOn
    Step.BG_LOCATION -> PIcons.MyLocation
    Step.NOTIFS -> Icons.Filled.Notifications
    Step.BATTERY -> PIcons.Battery
    Step.AUTOSTART -> PIcons.Power
    Step.SYNC -> PIcons.Cloud
}

/** Réglages applicables à ce téléphone, avec leur état (true = OK). */
fun protectionState(ctx: Context, autostartAck: Boolean, synced: Boolean): List<Pair<Step, Boolean>> = buildList {
    add(Step.ADMIN to Protection.isAdminActive(ctx))
    add(Step.LOCATION to Protection.hasLocation(ctx))
    if (Build.VERSION.SDK_INT >= 29) add(Step.BG_LOCATION to Protection.hasBackgroundLocation(ctx))
    if (Build.VERSION.SDK_INT >= 33) add(Step.NOTIFS to Protection.hasNotifications(ctx))
    add(Step.BATTERY to Protection.ignoresBattery(ctx))
    if (Protection.hasAutostartScreen) add(Step.AUTOSTART to autostartAck)
    add(Step.SYNC to synced)
}

@Composable
fun HomeScreen(onStep: (Step) -> Unit, onOwner: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val owner by Repo.owner.collectAsStateWithLifecycle()
    val autostartAck by Repo.autostartAck.collectAsStateWithLifecycle()
    val lastSync by Repo.lastSync.collectAsStateWithLifecycle()
    val syncError by Repo.syncError.collectAsStateWithLifecycle()
    val session by Repo.session.collectAsStateWithLifecycle()
    // Recalculé à chaque retour dans l'app (l'utilisateur revient des réglages système).
    val life by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val steps = remember(life, autostartAck, lastSync, session) {
        protectionState(ctx, autostartAck, synced = session != null && lastSync != null)
    }
    val done = steps.count { it.second }
    val all = done == steps.size

    GlassBackground {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 28.dp),
        ) {
            // ---------- Barre du haut
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("PhonePilote", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PP.Ink)
                    Text("Anti-vol & récupération", fontSize = 12.sp, color = PP.Muted)
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(PP.Glass).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, "Réglages", tint = PP.Ink)
                }
            }
            Spacer(Modifier.height(20.dp))

            // ---------- Statut
            StatusCard(all, done, steps.size, onFix = { steps.firstOrNull { !it.second }?.let { onStep(it.first) } })
            Spacer(Modifier.height(16.dp))

            // ---------- Propriétaire (ce que verra celui qui trouve le téléphone)
            GlassCard(Modifier.fillMaxWidth(), onClick = onOwner) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Filled.Phone, PP.Ink, PP.Yellow)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Numéro de secours", fontSize = 12.sp, color = PP.Muted)
                        Text(
                            owner?.let { Phone.pretty(it.emergencyPhone) } ?: "À renseigner",
                            style = MaterialTheme.typography.titleMedium, color = PP.Ink,
                        )
                        if (owner != null) Text(owner!!.name, fontSize = 13.sp, color = PP.Muted)
                    }
                    IconButton(onClick = onOwner) { Icon(Icons.Filled.Edit, "Modifier", tint = PP.Blue) }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---------- Liste des réglages
            Text("Réglages de protection", style = MaterialTheme.typography.titleMedium, color = PP.Ink,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            GlassCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                steps.forEachIndexed { i, (step, ok) ->
                    StepRow(step, ok) { onStep(step) }
                    if (i < steps.lastIndex) {
                        Box(Modifier.padding(start = 70.dp, end = 12.dp).fillMaxWidth().height(1.dp).background(PP.Line.copy(alpha = 0.6f)))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ---------- Ce téléphone
            Text("Ce téléphone", style = MaterialTheme.typography.titleMedium, color = PP.Ink,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(PIcons.Smartphone, PP.Blue, PP.BlueSoft)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                            style = MaterialTheme.typography.titleMedium, color = PP.Ink)
                        Text("Android ${Build.VERSION.RELEASE}", fontSize = 13.sp, color = PP.Muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                InfoLine("IMEI", owner?.imei?.ifBlank { null } ?: "Non renseigné (composez *#06#)")
                InfoLine(
                    "Dernière liaison",
                    lastSync?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "Jamais",
                )
                if (syncError != null) InfoLine("Erreur", syncError!!, PP.Danger)
            }
        }
    }
}

@Composable
private fun StatusCard(all: Boolean, done: Int, total: Int, onFix: () -> Unit) {
    // Carte héro : flat bleu (protégé) ou verre fort + accent jaune (incomplet).
    GlassCard(Modifier.fillMaxWidth(), strong = true, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(if (all) PP.Success else PP.Yellow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (all) PIcons.ShieldCheck else PIcons.Shield, null, Modifier.size(34.dp), tint = if (all) Color.White else PP.Ink)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (all) "Téléphone protégé" else "Protection incomplète",
                    style = MaterialTheme.typography.titleLarge, color = PP.Ink)
                Text("$done / $total réglages actifs", fontSize = 13.sp, color = PP.Muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { done / total.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = if (all) PP.Success else PP.Blue, trackColor = PP.Line, drawStopIndicator = {},
        )
        if (!all) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Terminer la configuration", onFix)
        }
    }
}

@Composable
private fun StepRow(step: Step, ok: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(step.icon(), if (ok) PP.Blue else PP.Muted, if (ok) PP.BlueSoft else PP.Line.copy(alpha = 0.6f), size = 42.dp, iconSize = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PP.Ink)
            Text(step.subtitle, fontSize = 12.sp, color = PP.Muted)
        }
        if (ok) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(PP.Success), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, "Actif", Modifier.size(16.dp), tint = Color.White)
            }
        } else {
            Pill("Activer", PP.Ink, PP.Yellow)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = PP.Muted)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, color: Color = PP.Ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = PP.Muted)
        Spacer(Modifier.width(16.dp))
        Text(value, fontSize = 13.sp, color = color, fontWeight = FontWeight.Medium)
    }
}
