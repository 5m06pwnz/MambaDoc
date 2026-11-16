package com.mambadoc.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mambadoc.app.ui.MambaDocNavHost
import com.mambadoc.app.ui.theme.MambaDocTheme
import com.mambadoc.app.data.model.SettingsRepository

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settingsRepo = (application as MambaDocApp).settingsRepo
            val settings by settingsRepo.settingsFlow.collectAsState(
                initial = SettingsRepository.DEFAULT
            )

            // FLAG_SECURE toggle (default ON for privacy)
            LaunchedEffect(settings.flagSecure) {
                if (settings.flagSecure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            MambaDocTheme(darkTheme = isSystemInDarkTheme() || settings.forceDark) {
                MambaDocNavHost()
            }
        }
    }
}
