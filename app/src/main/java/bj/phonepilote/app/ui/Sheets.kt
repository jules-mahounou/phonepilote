package bj.phonepilote.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import bj.phonepilote.app.BuildConfig
import bj.phonepilote.app.admin.Protection
import bj.phonepilote.app.admin.SmsCommands
import bj.phonepilote.app.data.Http
import bj.phonepilote.app.data.Owner
import bj.phonepilote.app.data.Phone
import bj.phonepilote.app.data.Repo
import bj.phonepilote.app.data.Supabase
import kotlinx.coroutines.launch

/** Contenu standard d'une feuille : marges, défilement, clavier. */
@Composable
private fun SheetBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)
            .navigationBarsPadding().imePadding(),
        content = content,
    )
}

// ================================================================ Onboarding

/**
 * Première ouverture : Bienvenue → Compte → Propriétaire → Activation.
 * Feuille verrouillée tant que le compte et le numéro de secours ne sont pas renseignés.
 */
@Composable
fun OnboardingSheet(onDone: () -> Unit) {
    val session by Repo.session.collectAsStateWithLifecycle()
    val demo by Repo.demoMode.collectAsStateWithLifecycle()
    val owner by Repo.owner.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    // Saute automatiquement les étapes déjà faites (ex. compte créé puis app fermée).
    val effective = when {
        page == 1 && (session != null || demo) -> 2
        page == 2 && owner != null -> 3
        else -> page
    }

    PSheet(onDismiss = onDone, locked = effective < 3) {
        SheetBody {
            StepDots(4, effective, Modifier.padding(bottom = 20.dp))
            when (effective) {
                0 -> Welcome { page = 1 }
                1 -> AccountForm(onDone = { page = 2 })
                2 -> OwnerForm(initial = null, onSaved = { page = 3 })
                else -> ActivateAdmin(onDone)
            }
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(96.dp)
        Spacer(Modifier.height(18.dp))
        Text("Bienvenue sur PhonePilote", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "Retrouvez votre téléphone s'il est égaré, verrouillez-le s'il est pris.",
            style = MaterialTheme.typography.bodyLarge, color = PP.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Spacer(Modifier.height(24.dp))
    Feature(Icons.Filled.LocationOn, "Localiser", "Voyez où se trouve votre téléphone depuis la plateforme.")
    Feature(Icons.Filled.Lock, "Verrouiller à distance", "L'écran se bloque et affiche votre numéro de secours.")
    Feature(PIcons.ShieldCheck, "Vos données restent intactes", "PhonePilote n'efface jamais rien.")
    Spacer(Modifier.height(24.dp))
    AccentButton("Commencer", onNext)
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), strong = true, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, PP.Blue, PP.BlueSoft, size = 42.dp, iconSize = 20.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(text, fontSize = 13.sp, color = PP.Muted)
            }
        }
    }
}

// ================================================================ Compte

/** Connexion / création de compte avec numéro de téléphone + mot de passe. */
@Composable
fun AccountForm(onDone: () -> Unit) {
    var signup by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val p = Phone.normalize(phone)
        err = when {
            signup && name.isBlank() -> "Indiquez votre nom"
            p == null -> "Numéro invalide (ex. 01 97 00 00 00)"
            pass.length < 6 -> "Mot de passe : 6 caractères minimum"
            else -> null
        }
        if (err != null || p == null) return
        busy = true
        scope.launch {
            try {
                if (signup) Repo.signUp(p, pass, name) else Repo.signIn(p, pass)
                onDone()
            } catch (e: Exception) {
                err = Http.friendly(e)
            } finally {
                busy = false
            }
        }
    }

    SheetHeader(
        if (signup) "Créez votre compte" else "Connexion",
        "Ce compte vous permettra de localiser ou verrouiller ce téléphone depuis un autre appareil.",
        icon = Icons.Filled.Person,
    )
    if (!Supabase.configured) {
        Notice("Plateforme pas encore configurée : vous pouvez tester l'app en mode démo.", PP.Warn, PP.YellowSoft)
        Spacer(Modifier.height(16.dp))
        AccentButton("Continuer en mode démo", { Repo.enterDemo(); onDone() })
        return
    }
    if (signup) {
        PField(name, { name = it }, "Nom complet", leading = Icons.Filled.Person)
        Spacer(Modifier.height(12.dp))
    }
    PField(
        phone, { phone = it }, "Numéro de téléphone", leading = Icons.Filled.Phone,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    Spacer(Modifier.height(12.dp))
    PField(
        pass, { pass = it }, "Mot de passe", password = true, leading = Icons.Filled.Lock,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    if (err != null) {
        Spacer(Modifier.height(12.dp))
        Notice(err!!, PP.Danger, PP.DangerSoft)
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton(if (signup) "Créer mon compte" else "Me connecter", ::submit, busy = busy)
    TextButton(onClick = { signup = !signup; err = null }, modifier = Modifier.fillMaxWidth()) {
        Text(if (signup) "J'ai déjà un compte" else "Créer un compte", color = PP.Blue)
    }
}

// ================================================================ Propriétaire

@Composable
fun OwnerForm(initial: Owner?, onSaved: () -> Unit) {
    val session by Repo.session.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(initial?.name ?: session?.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(initial?.emergencyPhone?.let { Phone.pretty(it) }.orEmpty()) }
    var imei by rememberSaveable { mutableStateOf(initial?.imei.orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }

    SheetHeader(
        "Numéro de secours",
        "Il s'affichera sur l'écran verrouillé pour que la personne qui trouve le téléphone puisse vous appeler. " +
            "Choisissez un numéro différent de celui de ce téléphone.",
        icon = Icons.Filled.Phone, tint = PP.Ink, tile = PP.Yellow,
    )
    PField(name, { name = it }, "Nom affiché", leading = Icons.Filled.Person)
    Spacer(Modifier.height(12.dp))
    PField(
        phone, { phone = it }, "Numéro à appeler", leading = Icons.Filled.Phone,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone),
        supporting = "Un proche, ou votre deuxième numéro",
    )
    Spacer(Modifier.height(12.dp))
    PField(
        imei, { v -> imei = v.filter { it.isDigit() }.take(15) }, "IMEI (facultatif)",
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
        supporting = "Composez *#06# pour l'afficher. Utile pour un blocage opérateur.",
    )
    if (err != null) {
        Spacer(Modifier.height(12.dp))
        Notice(err!!, PP.Danger, PP.DangerSoft)
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton("Enregistrer", {
        val p = Phone.normalize(phone)
        err = when {
            name.isBlank() -> "Indiquez le nom à afficher"
            p == null -> "Numéro invalide (ex. 01 97 00 00 00)"
            imei.isNotEmpty() && !Phone.isValidImei(imei) -> "IMEI invalide : vérifiez les 15 chiffres"
            else -> null
        }
        if (err == null && p != null) {
            Repo.saveOwner(Owner(name.trim(), p, imei))
            onSaved()
        }
    })
}

@Composable
fun OwnerSheet(onDismiss: () -> Unit) {
    val owner by Repo.owner.collectAsStateWithLifecycle()
    PSheet(onDismiss) { SheetBody { OwnerForm(owner, onDismiss) } }
}

// ================================================================ Activation Device Admin

@Composable
private fun ActivateAdmin(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val life by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = remember(life) { Protection.isAdminActive(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    SheetHeader(
        if (active) "Protection activée" else "Dernière étape",
        if (active) "Il reste quelques réglages rapides à faire depuis l'écran d'accueil."
        else "Android va vous demander d'autoriser PhonePilote à verrouiller l'écran. Appuyez sur « Activer ».",
        icon = if (active) PIcons.ShieldCheck else PIcons.Shield,
        tint = if (active) Color.White else PP.Ink, tile = if (active) PP.Success else PP.Yellow,
    )
    if (active) {
        PrimaryButton("Continuer", onDone)
    } else {
        AccentButton("Activer la protection", { launcher.launch(Protection.adminIntent(ctx)) }, icon = PIcons.Shield)
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Plus tard", color = PP.Muted) }
    }
}

// ================================================================ Réglage individuel

private fun Step.explain(): String = when (this) {
    Step.ADMIN -> "Autorise PhonePilote à verrouiller l'écran à distance. Rien n'est effacé, et la désactivation reste possible depuis les réglages Android."
    Step.SCREEN_LOCK -> "Indispensable : sans code, le verrouillage à distance éteint seulement l'écran et n'importe qui peut le rallumer. Choisissez un PIN, un schéma ou un mot de passe."
    Step.LOCATION -> "Permet d'envoyer la position du téléphone quand vous la demandez depuis la plateforme."
    Step.LOCATION_ON -> "Gardez la localisation du téléphone allumée : si elle est coupée, il est impossible de le localiser (seule la dernière position connue reste disponible)."
    Step.BG_LOCATION -> "Sans ce réglage, la position n'est disponible que lorsque l'app est ouverte. Choisissez « Toujours autoriser »."
    Step.NOTIFS -> "PhonePilote vous prévient quand une commande est reçue ou qu'un réglage se désactive."
    Step.BATTERY -> "Sinon, Android met PhonePilote en veille et les commandes (localiser, verrouiller) arrivent en retard ou jamais."
    Step.AUTOSTART -> "Sur ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }}, autorisez PhonePilote à démarrer automatiquement, puis revenez ici."
    Step.SYNC -> "Relie ce téléphone à votre compte pour pouvoir le localiser et le verrouiller depuis un autre appareil."
}

@Composable
fun StepSheet(step: Step, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val life by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val autostartAck by Repo.autostartAck.collectAsStateWithLifecycle()
    val session by Repo.session.collectAsStateWithLifecycle()
    val lastSync by Repo.lastSync.collectAsStateWithLifecycle()
    val syncError by Repo.syncError.collectAsStateWithLifecycle()
    var refusals by rememberSaveable { mutableIntStateOf(0) }
    val ok = remember(life, autostartAck, session, lastSync, refusals) {
        protectionState(ctx, autostartAck, session != null && lastSync != null).firstOrNull { it.first == step }?.second ?: true
    }

    val activity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { !it }) refusals++
    }
    // Après 2 refus, Android n'affiche plus la demande : on envoie vers la fiche de l'app.
    fun ask(vararg p: String) {
        if (refusals >= 2) activity.launch(Protection.appSettingsIntent(ctx)) else perms.launch(arrayOf(*p))
    }

    fun act() {
        when (step) {
            Step.ADMIN -> activity.launch(Protection.adminIntent(ctx))
            Step.SCREEN_LOCK -> runCatching { activity.launch(Protection.screenLockIntent()) }
                .onFailure { activity.launch(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }
            Step.LOCATION_ON -> activity.launch(Protection.locationSettingsIntent())
            Step.LOCATION -> ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            Step.BG_LOCATION ->
                if (!Protection.hasLocation(ctx)) ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                else if (Build.VERSION.SDK_INT >= 29) ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            Step.NOTIFS -> if (Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.POST_NOTIFICATIONS)
            Step.BATTERY -> runCatching { activity.launch(Protection.batteryIntent(ctx)) }
                .onFailure { activity.launch(Protection.appSettingsIntent(ctx)) }
            Step.AUTOSTART -> Protection.openAutostart(ctx)
            Step.SYNC -> Repo.sync()
        }
    }

    PSheet(onDismiss) {
        SheetBody {
            SheetHeader(
                step.title, step.explain(), icon = step.icon(),
                tint = if (ok) Color.White else PP.Blue, tile = if (ok) PP.Success else PP.BlueSoft,
            )
            if (step == Step.SYNC && session == null) {
                AccountForm(onDone = {})
                return@SheetBody
            }
            if (ok) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PP.SuccessSoft).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(PP.Success), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("C'est activé", color = PP.Success, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Fermer", onDismiss)
            } else {
                if (step == Step.SYNC && syncError != null) {
                    Notice(syncError!!, PP.Danger, PP.DangerSoft)
                    Spacer(Modifier.height(12.dp))
                }
                if (step == Step.BG_LOCATION && Build.VERSION.SDK_INT >= 30) {
                    Notice("Android va ouvrir les réglages : choisissez « Toujours autoriser ».", PP.BlueDeep, PP.BlueSoft)
                    Spacer(Modifier.height(12.dp))
                }
                AccentButton(if (step == Step.SYNC) "Relier maintenant" else "Activer", ::act)
                if (step == Step.AUTOSTART) {
                    Spacer(Modifier.height(10.dp))
                    GhostButton("C'est fait", { Repo.ackAutostart() })
                }
            }
        }
    }
}

// ================================================================ Réglages

@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val session by Repo.session.collectAsStateWithLifecycle()
    PSheet(onDismiss) {
        SheetBody {
            SheetHeader("Réglages", icon = Icons.Filled.Info)
            GlassCard(Modifier.fillMaxWidth(), strong = true) {
                Text("Compte", fontSize = 12.sp, color = PP.Muted)
                Text(
                    session?.let { Phone.pretty(it.phone) } ?: "Non connecté",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!session?.name.isNullOrBlank()) Text(session!!.name, fontSize = 13.sp, color = PP.Muted)
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), strong = true) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Version", fontSize = 13.sp, color = PP.Muted)
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("Identifiant", fontSize = 13.sp, color = PP.Muted)
                    Text(Repo.deviceId.take(8).uppercase(), fontSize = 13.sp)
                }
            }
            if (session != null) {
                Spacer(Modifier.height(12.dp))
                SmsSection(session!!.phone)
                Spacer(Modifier.height(20.dp))
                GhostButton("Se déconnecter", { Repo.signOut(); onDismiss() }, color = PP.Danger)
            }
        }
    }
}

/** Commandes par SMS : choix du code secret, permissions, mode d'emploi. */
@Composable
private fun SmsSection(ownerPhone: String) {
    val ctx = LocalContext.current
    val life by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var version by remember { mutableIntStateOf(0) } // force le recalcul après une modification
    val codeSet = remember(life, version) { SmsCommands.isCodeSet(ctx) }
    val perms = remember(life, version) { SmsCommands.hasPermissions(ctx) }
    val allowed = remember(life, version) { SmsCommands.remoteAllowed(ctx) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        version++
        Repo.sync()
    }

    fun save() {
        err = SmsCommands.validate(code) ?: if (code != confirm) "Les deux codes ne correspondent pas" else null
        if (err != null) return
        SmsCommands.setCode(ctx, code)
        code = ""; confirm = ""; editing = false; version++
        if (!SmsCommands.hasPermissions(ctx)) askPerms.launch(SmsCommands.permissions) else Repo.sync()
    }

    GlassCard(Modifier.fillMaxWidth(), strong = true) {
        Text("Commandes par SMS (sans internet)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                !codeSet -> "Non configuré"
                !perms -> "Permissions SMS manquantes"
                else -> "Actif"
            },
            fontSize = 13.sp, color = if (codeSet && perms) PP.Success else PP.Muted, fontWeight = FontWeight.SemiBold,
        )
        if (!allowed) {
            Spacer(Modifier.height(10.dp))
            Notice("En attendant les agréments, les commandes SMS ne fonctionnent que sur les téléphones de test.", PP.BlueDeep, PP.BlueSoft)
        }
        if (codeSet && !editing) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Depuis n'importe quel téléphone, envoyez au ${Phone.pretty(ownerPhone)} :\n" +
                    "• PP LOCK <code> : verrouille\n• PP LOCATE <code> : répond avec la position\n\n" +
                    "Envoyez toujours LOCK en premier : le SMS reste visible dans la messagerie tant que l'écran n'est pas verrouillé. " +
                    "Après avoir récupéré le téléphone, changez de code.",
                fontSize = 13.sp, color = PP.Muted, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (!perms) {
                AccentButton("Autoriser les SMS", { askPerms.launch(SmsCommands.permissions) })
                Spacer(Modifier.height(8.dp))
            }
            GhostButton("Changer le code", { editing = true })
            GhostButton("Désactiver", { SmsCommands.clearCode(ctx); version++; Repo.sync() }, color = PP.Danger)
        } else {
            if (!codeSet && !editing) {
                Spacer(Modifier.height(12.dp))
                AccentButton("Choisir un code secret", { editing = true })
            }
        }
        if (editing) {
            Spacer(Modifier.height(12.dp))
            PField(
                code, { v -> code = v.filter { it.isDigit() }.take(6) }, "Code secret (6 chiffres)", password = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supporting = "Ne le donnez à personne. Évitez votre date de naissance.",
            )
            Spacer(Modifier.height(8.dp))
            PField(
                confirm, { v -> confirm = v.filter { it.isDigit() }.take(6) }, "Confirmer le code", password = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            if (err != null) {
                Spacer(Modifier.height(8.dp))
                Notice(err!!, PP.Danger, PP.DangerSoft)
            }
            Spacer(Modifier.height(12.dp))
            AccentButton("Enregistrer", ::save)
            GhostButton("Annuler", { editing = false; code = ""; confirm = ""; err = null })
        }
    }
}

// ================================================================ Divers

@Composable
fun Notice(text: String, fg: Color, bg: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, null, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(10.dp))
        Text(text, color = fg, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
