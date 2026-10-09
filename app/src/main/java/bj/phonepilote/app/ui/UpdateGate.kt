package bj.phonepilote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import bj.phonepilote.app.BuildConfig
import bj.phonepilote.app.data.Repo
import bj.phonepilote.app.update.Updater
import kotlinx.coroutines.launch

/**
 * Mise à jour (repris de xyd) : si la version installée est < min_version_code, la feuille est verrouillée.
 * Sinon elle est proposée une fois par version.
 */
@Composable
fun UpdateGate() {
    val v by Repo.update.collectAsStateWithLifecycle()
    val state by Updater.state.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val life by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val canInstall = remember(life) { Updater.canInstall(ctx) }
    val info = v ?: return
    val forced = BuildConfig.VERSION_CODE < info.minVersionCode
    if (!forced && dismissed == info.versionCode) return

    PSheet(onDismiss = { dismissed = info.versionCode }, locked = forced) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Logo(64.dp)
            Spacer(Modifier.height(16.dp))
            Text(if (forced) "Mise à jour obligatoire" else "Mise à jour disponible", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Version ${info.versionName.ifBlank { info.versionCode.toString() }}",
                color = PP.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
            if (info.changelog.isNotBlank()) {
                Text(
                    info.changelog, color = PP.Ink, fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (forced) {
                Text(
                    "Installez cette version pour que la protection continue de fonctionner.",
                    color = PP.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))

            when (val s = state) {
                is Updater.State.Downloading -> {
                    LinearProgressIndicator(
                        progress = { s.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = PP.Blue, trackColor = PP.Line, drawStopIndicator = {},
                    )
                    Text("${(s.fraction * 100).toInt()} %", color = PP.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                else -> {
                    if (s is Updater.State.Error) {
                        Notice(s.message, PP.Danger, PP.DangerSoft)
                        Spacer(Modifier.height(12.dp))
                    }
                    if (!canInstall) {
                        Text(
                            "Autorisez d'abord PhonePilote à installer des applications (une seule fois).",
                            color = PP.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    PrimaryButton(
                        when {
                            !canInstall -> "Autoriser l'installation"
                            s is Updater.State.Ready -> "Installer"
                            else -> "Mettre à jour"
                        },
                        {
                            when {
                                !canInstall -> Updater.openInstallPermission(ctx)
                                s is Updater.State.Ready -> Updater.install(ctx)
                                else -> scope.launch { Updater.download(ctx, info.apkUrl) }
                            }
                        },
                    )
                    if (!forced) {
                        TextButton(onClick = { dismissed = info.versionCode }) { Text("Plus tard", color = PP.Muted) }
                    }
                }
            }
        }
    }
}
