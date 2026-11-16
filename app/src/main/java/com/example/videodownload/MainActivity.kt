package com.example.videodownload

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.videodownload.ui.HomeScreen
import com.example.videodownload.ui.MainViewModel
import com.example.videodownload.ui.SplashScreen
import com.example.videodownload.ui.theme.VideoDownloadTheme
import com.example.videodownload.utils.SettingsManager

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Procesar si la app fue abierta desde el menú "Compartir" de otra app (TikTok, YouTube, IG, etc.)
        handleIncomingIntent(intent)

        setContent {
            // Modo de tema persistido: system / dark / light
            var themeMode by remember {
                mutableStateOf(SettingsManager.getThemeMode(this))
            }

            val darkTheme = when (themeMode) {
                SettingsManager.THEME_DARK -> true
                SettingsManager.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            VideoDownloadTheme(darkTheme = darkTheme) {
                val showSplash = remember { mutableStateOf(true) }

                Crossfade(
                    targetState = showSplash.value,
                    animationSpec = tween(durationMillis = 500),
                    label = "SplashToHomeTransition"
                ) { isSplash ->
                    if (isSplash) {
                        SplashScreen(
                            onSplashFinished = { showSplash.value = false }
                        )
                    } else {
                        HomeScreen(
                            viewModel = viewModel,
                            themeMode = themeMode,
                            onThemeChange = { mode ->
                                themeMode = mode
                                SettingsManager.setThemeMode(this, mode)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.handleSharedUrl(sharedText)
            }
        }
    }
}
