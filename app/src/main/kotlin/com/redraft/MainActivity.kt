package com.redraft

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redraft.ui.nav.RedraftNavHost
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.settings.SettingsViewModel
import com.redraft.ui.theme.RedraftTheme
import com.redraft.ui.theme.ThemeMode
import com.redraft.data.AppSettings

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
            
            // Handle incoming intent data (when opened via "Open With" from file managers)
            val incomingUri = handleIntent(intent)
            
            RedraftTheme(
                themeMode = settings.value?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = settings.value?.dynamicColor ?: true,
                amoled = settings.value?.amoled ?: false,
            ) {
                RedraftNavHost(
                    settingsViewModel = settingsViewModel,
                    pdfViewerViewModel = pdfViewerViewModel,
                    initialUri = incomingUri // Pass this down to your navigation graph if needed
                )
            }
        }
    }

    private fun handleIntent(intent: Intent?): Uri? {
        return if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            // Take persistable URI permissions so we can read/write the file later
            uri?.let {
                try {
                    val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(it, flag)
                } catch (e: Exception) {
                    // Some content providers don't support persistable permissions, which is fine
                }
            }
            uri
        } else {
            null
        }
    }
}