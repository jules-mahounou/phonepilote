package bj.phonepilote.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import bj.phonepilote.app.data.Repo
import bj.phonepilote.app.ui.HomeScreen
import bj.phonepilote.app.ui.OnboardingSheet
import bj.phonepilote.app.ui.OwnerSheet
import bj.phonepilote.app.ui.PP
import bj.phonepilote.app.ui.PhonePiloteTheme
import bj.phonepilote.app.ui.SettingsSheet
import bj.phonepilote.app.ui.Step
import bj.phonepilote.app.ui.StepSheet
import bj.phonepilote.app.ui.UpdateGate
import bj.phonepilote.app.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Une seule activité, un écran (Accueil), tout le reste en bottom sheets. */
class MainActivity : ComponentActivity() {
    private var watcher: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            PhonePiloteTheme {
                val session by Repo.session.collectAsStateWithLifecycle()
                val demo by Repo.demoMode.collectAsStateWithLifecycle()
                val owner by Repo.owner.collectAsStateWithLifecycle()
                // Reste ouvert jusqu'à la dernière étape (activation), même une fois compte et numéro saisis.
                var onboarding by rememberSaveable { mutableStateOf(false) }
                var step by rememberSaveable { mutableStateOf<Step?>(null) }
                var ownerOpen by rememberSaveable { mutableStateOf(false) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                val needsOnboarding = (session == null && !demo) || owner == null
                LaunchedEffect(needsOnboarding) { if (needsOnboarding) onboarding = true }

                Surface(Modifier.fillMaxSize(), color = PP.Bg, contentColor = PP.Ink) {
                    Box(Modifier.fillMaxSize()) {
                        HomeScreen(
                            onStep = { step = it },
                            onOwner = { ownerOpen = true },
                            onSettings = { settingsOpen = true },
                        )
                        when {
                            needsOnboarding || onboarding -> OnboardingSheet(onDone = { onboarding = false })
                            step != null -> StepSheet(step!!, onDismiss = { step = null })
                            ownerOpen -> OwnerSheet(onDismiss = { ownerOpen = false })
                            settingsOpen -> SettingsSheet(onDismiss = { settingsOpen = false })
                        }
                        UpdateGate()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Repo.sync()
        // Vérifie les mises à jour au retour au premier plan puis toutes les 10 min tant que l'app est visible.
        watcher?.cancel()
        watcher = lifecycleScope.launch {
            while (isActive) {
                Repo.checkUpdate()
                if (Repo.update.value == null) Updater.cleanup(this@MainActivity)
                delay(10 * 60 * 1000L)
            }
        }
    }

    override fun onStop() {
        watcher?.cancel()
        super.onStop()
    }
}
