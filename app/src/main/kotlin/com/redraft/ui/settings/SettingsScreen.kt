package com.redraft.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.R
import com.redraft.data.AppSettings
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    pdfViewerViewModel: PdfViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val cacheSize by pdfViewerViewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val settingsState = settings ?: AppSettings()

    LaunchedEffect(Unit) {
        pdfViewerViewModel.refreshCacheSize()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { SectionHeader("Appearance") }
            item { ThemeModeRadioGroup(settingsState, settingsViewModel) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item { DynamicColorSwitch(settingsState, settingsViewModel) }
            }
            item { AmoledSwitch(settingsState, settingsViewModel) }
            item { Divider() }
            item { SectionHeader("Viewer") }
            item { KeepScreenOnSwitch(settingsState, settingsViewModel) }
            item { ReopenAtLastPageSwitch(settingsState, settingsViewModel) }
            item { Divider() }
            item { SectionHeader("Storage") }
            item { ClearRecentsButton(settingsViewModel) }
            item { ClearCacheButton(pdfViewerViewModel) }
            item { Divider() }
            item { SectionHeader("About") }
            item { AboutSupportRow(title = "About Redraft", onClick = { }) }
            item { AboutSupportRow(title = "Support Redraft", onClick = { }) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun ThemeModeRadioGroup(settings: AppSettings, viewModel: SettingsViewModel) {
    val modes = listOf<Pair<ThemeMode, String>>(
        ThemeMode.SYSTEM to "System",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark",
    )
    for (i in 0 until modes.size) {
        val mode = modes[i].first
        val label = modes[i].second
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = settings.themeMode == mode, onClick = { viewModel.setThemeMode(mode) })
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            RadioButton(selected = settings.themeMode == mode, onClick = null)
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp)
            )
        }
    }
}

@Composable
private fun DynamicColorSwitch(settings: AppSettings, viewModel: SettingsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.dynamicColor, onValueChange = { viewModel.setDynamicColor(it) })
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = "Dynamic colour", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Switch(checked = settings.dynamicColor, onCheckedChange = null)
    }
}

@Composable
private fun AmoledSwitch(settings: AppSettings, viewModel: SettingsViewModel) {
    val disabled = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.amoled, onValueChange = { if (!disabled) viewModel.setAmoled(it) })
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = "AMOLED black", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Switch(checked = settings.amoled, onCheckedChange = null, enabled = !disabled)
    }
}

@Composable
private fun KeepScreenOnSwitch(settings: AppSettings, viewModel: SettingsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.keepScreenOn, onValueChange = { viewModel.setKeepScreenOn(it) })
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = "Keep screen on while reading", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Switch(checked = settings.keepScreenOn, onCheckedChange = null)
    }
}

@Composable
private fun ReopenAtLastPageSwitch(settings: AppSettings, viewModel: SettingsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.reopenAtLastPage, onValueChange = { viewModel.setReopenAtLastPage(it) })
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = "Reopen at the last page", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Switch(checked = settings.reopenAtLastPage, onCheckedChange = null)
    }
}

@Composable
private fun ClearRecentsButton(viewModel: SettingsViewModel) {
    ListItem(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = { }),
        leadingContent = { Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(text = "Clear list", style = MaterialTheme.typography.titleMedium) }
    )
}

@Composable
private fun ClearCacheButton(pdfViewerViewModel: PdfViewerViewModel) {
    ListItem(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = { pdfViewerViewModel.clearCaches() }),
        leadingContent = { Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(text = "Clear cache", style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(text = "Cached pages and thumbnails: Checking…", style = MaterialTheme.typography.bodyMedium) }
    )
}

@Composable
private fun AboutSupportRow(title: String, onClick: () -> Unit) {
}
