package com.redraft

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redraft.ui.nav.RedraftNavHost
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.settings.SettingsViewModel
import com.redraft.ui.theme.RedraftTheme
import com.redraft.ui.theme.ThemeMode
import com.redraft.data.AppSettings
import com.redraft.data.SafDocumentOpener

class MainActivity : ComponentActivity() {
    // Created by the activity itself, NOT inside setContent: Compose composes after onCreate returns,
    // so a ViewModel assigned from the composition is not there yet when the intent is handled
    // (that was the "Open with" crash). viewModel(factory) in setContent resolves to this same instance.
    private val pdfViewerViewModel: PdfViewerViewModel by viewModels { PdfViewerViewModel.factory(this) }

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
            val vm: PdfViewerViewModel = viewModel(factory = PdfViewerViewModel.factory(this))
            val settings = settingsViewModel.settings.collectAsStateWithLifecycle()

            RedraftTheme(
                themeMode = settings.value?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = settings.value?.dynamicColor ?: true,
                amoled = settings.value?.amoled ?: false,
            ) {
                RedraftNavHost(
                    settingsViewModel = settingsViewModel,
                    pdfViewerViewModel = vm,
                )
            }
        }
        // Only on a fresh launch: after rotation the intent is the same one and must not re-open.
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // Keep the grant alive if the sender offers one; a plain temporary grant is fine too,
        // the file is read straight away.
        try {
            SafDocumentOpener.persistPermission(this, uri)
        } catch (_: Exception) {
            // Not persistable (temporary grant, file:// URI): the file is read right away anyway.
        }
        pdfViewerViewModel.open(uri.toString())
        // Consume it so a config change / recreate does not open it a second time.
        intent.action = null
    }
}
