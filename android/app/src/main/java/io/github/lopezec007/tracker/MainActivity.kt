package io.github.lopezec007.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lopezec007.tracker.location.TrackerService
import io.github.lopezec007.tracker.ui.TrackerViewModel
import io.github.lopezec007.tracker.ui.main.MainScreen
import io.github.lopezec007.tracker.ui.nav.Nav
import io.github.lopezec007.tracker.ui.nav.Screen
import io.github.lopezec007.tracker.ui.settings.SettingsScreen
import io.github.lopezec007.tracker.ui.setup.SetupScreen
import io.github.lopezec007.tracker.ui.theme.TrackerTheme

class MainActivity : ComponentActivity() {
    private val vm: TrackerViewModel by viewModels { TrackerViewModel.Factory(TrackerApp.graph(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The service is started whenever the app is opened and allowed to
        // run (setup done, location granted); starting it again is harmless.
        vm.startServiceIfAllowed(this)
    }

    @Composable
    private fun Root(vm: TrackerViewModel) {
        val prefs by vm.prefs.collectAsStateWithLifecycle()
        val p = prefs ?: return  // DataStore not read yet: keep the window background
        val nav = remember { Nav(if (p.isConfigured) Screen.Main else Screen.Setup) }

        LaunchedEffect(p.isConfigured) {
            if (!p.isConfigured && nav.current != Screen.Setup) nav.reset(Screen.Setup)
            if (TrackerService.shouldRun(this@MainActivity, p)) TrackerService.start(this@MainActivity)
        }

        BackHandler(enabled = nav.canGoBack) { nav.pop() }

        when (nav.current) {
            Screen.Setup -> SetupScreen(vm, onDone = { nav.reset(Screen.Main) })
            Screen.Main -> MainScreen(vm, nav)
            Screen.Settings -> SettingsScreen(vm, nav)
        }
    }
}
