package com.redraft

import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redraft.ui.nav.RedraftNavHost
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.settings.SettingsViewModel
import com.redraft.ui.theme.RedraftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(this))
            val pdfViewerViewModel: PdfViewerViewModel = viewModel(factory = PdfViewerViewModel.factory(this))
            val settings = settingsViewModel.settings.collectAsStateWithLifecycle()
            RedraftTheme(
                themeMode = settings.value.themeMode,
                dynamicColor = settings.value.dynamicColor,
                amoled = settings.value.amoled,
            ) {
                RedraftNavHost(
                    settingsViewModel = settingsViewModel,
                    pdfViewerViewModel = pdfViewerViewModel,
                )
            }
        }
    }
}