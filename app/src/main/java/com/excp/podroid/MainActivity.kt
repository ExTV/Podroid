package com.excp.podroid

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.data.repository.LanguageManager
import com.excp.podroid.ui.navigation.NavGraphViewModel
import com.excp.podroid.ui.navigation.PodroidNavGraph
import com.excp.podroid.ui.theme.PodroidTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context?) {
        val base = newBase ?: return super.attachBaseContext(null)
        val savedLang = LanguageManager.getSavedLanguage(base)
        super.attachBaseContext(LanguageManager.wrapContextForLocale(base, savedLang))
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val navVm: NavGraphViewModel = hiltViewModel()
            val headlessVm: com.excp.podroid.ui.HeadlessViewModel = hiltViewModel()
            val darkTheme by navVm.darkTheme.collectAsStateWithLifecycle(initialValue = null)
            val dynamicColor by navVm.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = false)
            val headlessActive by headlessVm.active.collectAsStateWithLifecycle()

            // Server mode: near-zero window brightness + keep the screen on so the
            // app stays foreground (the VM is never backgrounded -> never killed).
            androidx.compose.runtime.LaunchedEffect(headlessActive) {
                val lp = window.attributes
                if (headlessActive) {
                    lp.screenBrightness = 0.004f
                    window.attributes = lp
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lp
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            PodroidTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        PodroidNavGraph(windowSizeClass = windowSizeClass)
                        if (headlessActive) {
                            com.excp.podroid.ui.components.HeadlessOverlay(onExit = { headlessVm.disable() })
                        }
                    }
                }
            }
        }
    }
}
