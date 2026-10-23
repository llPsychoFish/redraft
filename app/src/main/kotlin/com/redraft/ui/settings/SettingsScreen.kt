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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.R
import com.redraft.data.AppSettings
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    pdfViewerViewModel: PdfViewerViewModel,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val cacheSize by pdfViewerViewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val settingsState = settings ?: AppSettings()

    val scope = rememberCoroutineScope()

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
            item { AmoledSwitch(settingsState, settingsViewModel, snackbarHostState) }
            item { HorizontalDivider() }
            item { SectionHeader("Viewer") }
            item { KeepScreenOnSwitch(settingsState, settingsViewModel) }
            item { ReopenAtLastPageSwitch(settingsState, settingsViewModel) }
            item { HorizontalDivider() }
            item { SectionHeader("Storage") }
            item { ClearRecentsButton(onClear = { 
                pdfViewerViewModel.clearRecents()
                scope.launch { snackbarHostState.showSnackbar("Recent files cleared") }
            }) }
            item {
                ClearCacheButton(
                    cacheSize = cacheSize,
                    onClear = {
                        pdfViewerViewModel.clearCaches()
                        pdfViewerViewModel.refreshCacheSize()
                        scope.launch { snackbarHostState.showSnackbar("Cache cleared") }
                    },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader("About") }
            item { NavigationRow(title = "About Redraft", onClick = onOpenAbout) }
            item { NavigationRow(title = "Support Redraft", onClick = onOpenSupport) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun ThemeModeRadioGroup(settings: AppSettings, viewModel: SettingsViewModel) {
    val modes = listOf<Pair<ThemeMode, String>>(
        ThemeMode.SYSTEM to "System",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
    ) {
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
}

@Composable
private fun DynamicColorSwitch(settings: AppSettings, viewModel: SettingsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.dynamicColor, onValueChange = { viewModel.setDynamicColor(it) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Dynamic colour", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = settings.dynamicColor, onCheckedChange = null)
    }
}

@Composable
private fun AmoledSwitch(settings: AppSettings, viewModel: SettingsViewModel, snackbarHostState: SnackbarHostState) {
    val disabled = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.amoled, onValueChange = { if (!disabled) viewModel.setAmoled(it) }, enabled = !disabled)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "AMOLED black", style = MaterialTheme.typography.titleMedium)
            if (disabled) {
                Text(
                    text = "Turn off Dynamic colour to use AMOLED black",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        Switch(checked = settings.amoled, onCheckedChange = null, enabled = !disabled)
    }
}

@Composable
private fun KeepScreenOnSwitch(settings: AppSettings, viewModel: SettingsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.keepScreenOn, onValueChange = { viewModel.setKeepScreenOn(it) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Keep screen on while reading", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = settings.keepScreenOn, onCheckedChange = null)
    }
}

@Composable
private fun ReopenAtLastPageSwitch(settings: AppSettings, viewModel: SettingsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.reopenAtLastPage, onValueChange = { viewModel.setReopenAtLastPage(it) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Reopen at the last page", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = settings.reopenAtLastPage, onCheckedChange = null)
    }
}

@Composable
private fun ClearRecentsButton(onClear: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { confirming = true },
        leadingContent = { Icon(painter = painterResource(R.drawable.ic_storage), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(text = "Clear list", style = MaterialTheme.typography.titleMedium) }
    )

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove recent files?") },
            text = {
                Text(
                    "This removes the recent-files list from this device. " +
                        "The files themselves are not deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClear()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun ClearCacheButton(cacheSize: Long?, onClear: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClear),
        leadingContent = { Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(text = "Clear cache", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                text = "Cached pages and thumbnails: ${formatCacheSize(cacheSize)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    )
}

@Composable
private fun NavigationRow(title: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        headlineContent = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}