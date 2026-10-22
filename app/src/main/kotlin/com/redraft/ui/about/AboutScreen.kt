package com.redraft.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.redraft.BuildConfig
import com.redraft.R
import com.redraft.ui.support.SupportLinks
import com.redraft.ui.support.SupportLink
import com.redraft.ui.support.openExternalLink
import kotlinx.coroutines.launch

/**
 * One third-party library and the licence it actually ships under.
 *
 * Every entry here was verified from the artifact this build resolves, not from memory (D-17):
 * the Maven POMs in the Gradle cache for the Kotlin, coroutines, pdfiumandroid and AndroidX
 * coordinates, and PDFium's own upstream `LICENSE` for the bundled native library.
 */
private data class Notice(val library: String, val licence: String)

private val openSourceNotices = listOf(
    Notice("PDFium (Chromium)", "BSD-3-Clause and Apache-2.0"),
    Notice("pdfiumandroid (io.legere)", "Apache-2.0"),
    Notice("Kotlin standard library", "Apache-2.0"),
    Notice("Kotlin coroutines", "Apache-2.0"),
    Notice("AndroidX: Compose, Lifecycle, Navigation, DataStore, Activity, Core", "Apache-2.0"),
)

private const val STATUS_LINE = "Viewer today; true content editing is in development."
private const val DESCRIPTION = "A free, offline PDF viewer for Android."
private const val PRIVACY_1 =
    "Redraft works entirely offline. It has no internet permission, so it cannot send your " +
        "documents anywhere. Files you open stay on this device. There is no analytics, no " +
        "crash reporting, and no tracking."
private const val PRIVACY_2 =
    "The only stored data is your recent-files list and your settings, both kept on this device."
private const val LICENCES_SUBTITLE =
    "Redraft is free software. The libraries it uses keep their own licences, listed below."
private const val LINK_ERROR = "Couldn't open the link. No app on this device can handle it."

/**
 * About surface: identity, the honest status line (D-16), privacy (D-18), verified open-source
 * notices (D-17), the bundled MIT licence, and the Support entry point (D-19).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val links: List<SupportLink> = remember { SupportLinks.configured(SupportLinks.aboutLinks) }
    var showLicences by remember { mutableStateOf(false) }
    var showLicenceText by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }

    val licenceText = remember {
        runCatching {
            context.resources.openRawResource(R.raw.licence).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("About Redraft") },
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
        bottomBar = {
            Button(
                onClick = onSupport,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text("Support Redraft")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.redraft_icon),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.Unspecified,
                        )
                        Text(text = "Redraft", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            text = DESCRIPTION,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = STATUS_LINE,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FeatureChip("Open source", R.drawable.ic_github)
                            FeatureChip("Offline-first", R.drawable.ic_cloud_off)
                            FeatureChip("No tracking", R.drawable.ic_shield)
                        }
                    }
                }
            }

            item { SectionHeader("Links") }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { showLicences = true },
                            leadingContent = { LeadingIcon(R.drawable.ic_article) },
                            headlineContent = {
                                Text(
                                    text = "Open-source licences",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                            trailingContent = { Chevron() },
                        )
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { showLicenceText = true },
                            leadingContent = { LeadingIcon(R.drawable.ic_gavel) },
                            headlineContent = {
                                Text(text = "Licence", style = MaterialTheme.typography.titleMedium)
                            },
                            trailingContent = { Chevron() },
                        )
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { showPrivacy = true },
                            leadingContent = { LeadingIcon(R.drawable.ic_shield) },
                            headlineContent = {
                                Text(text = "Privacy", style = MaterialTheme.typography.titleMedium)
                            },
                            trailingContent = { Chevron() },
                        )
                        links.forEach { link ->
                            ListItem(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (!openExternalLink(context, link.url)) {
                                        scope.launch { snackbarHostState.showSnackbar(LINK_ERROR) }
                                    }
                                },
                                leadingContent = { LeadingIcon(aboutLinkIcon(link.label)) },
                                headlineContent = {
                                    Text(
                                        text = link.label,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_open_in_new),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Redraft v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                        "— MIT licence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
        }
    }

    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text("Open-source licences") },
            text = {
                Column {
                    Text(
                        text = LICENCES_SUBTITLE,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    openSourceNotices.forEach { notice ->
                        Text(
                            text = "${notice.library} — ${notice.licence}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicences = false }) { Text("Close") }
            },
        )
    }

    if (showLicenceText) {
        AlertDialog(
            onDismissRequest = { showLicenceText = false },
            title = { Text("MIT licence") },
            text = {
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    item {
                        Text(text = licenceText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenceText = false }) { Text("Close") }
            },
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = PRIVACY_1, style = MaterialTheme.typography.bodyMedium)
                    Text(text = PRIVACY_2, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) { Text("Close") }
            },
        )
    }
}

/** Leading icon for an external About link; unmapped labels get a neutral glyph. */
private fun aboutLinkIcon(label: String): Int = when (label) {
    "Source code" -> R.drawable.ic_github
    "Report an issue" -> R.drawable.ic_bug
    else -> R.drawable.ic_open_in_new
}

@Composable
private fun FeatureChip(label: String, iconRes: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun LeadingIcon(iconRes: Int) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}
