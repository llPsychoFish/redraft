package com.redraft.ui.support

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.redraft.R
import kotlinx.coroutines.launch

/** Copy fixed by the UI contract (01.1-UI-SPEC § Support and § Errors). */
private const val SUPPORT_MESSAGE =
    "Redraft is free and open source, with no ads, no accounts, and nothing collected. " +
        "If it saves you time, you can support its development — but every feature works " +
        "exactly the same either way."
private const val NO_LINKS = "No support links are set up in this build."
private const val LINK_ERROR = "Couldn't open the link. No app on this device can handle it."
private const val ADDRESS_COPIED = "Address copied"

/**
 * Support/donations surface. External links only: no billing library, no gating, no nagging
 * (D-19, D-21). A build without configured URLs is a normal, honest state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val links = remember { SupportLinks.configured(SupportLinks.supportLinks) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Support Redraft") },
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
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = SUPPORT_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }

            item { SectionHeader("Ways to support") }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    if (links.isEmpty()) {
                        Text(
                            text = NO_LINKS,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    } else {
                        links.forEach { link ->
                            val (iconRes, subtitle) = supportPresentation(link.label)
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!openExternalLink(context, link.url)) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(LINK_ERROR)
                                            }
                                        }
                                    },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = link.label,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                },
                                supportingContent = subtitle?.let { text ->
                                    {
                                        Text(
                                            text = text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
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

            if (SupportLinks.XMR_ADDRESS.isNotBlank()) {
                item { SectionHeader("Crypto") }
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                ClipData.newPlainText(
                                                    "Monero address",
                                                    SupportLinks.XMR_ADDRESS,
                                                ),
                                            ),
                                        )
                                        snackbarHostState.showSnackbar(ADDRESS_COPIED)
                                    }
                                },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_currency_btc),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = "Monero (XMR)",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = SupportLinks.XMR_ADDRESS,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Curated icon + subtitle per known platform; unmapped labels get a neutral icon and no subtitle. */
private fun supportPresentation(label: String): Pair<Int, String?> = when (label) {
    "GitHub Sponsors" -> R.drawable.ic_heart to "Recurring monthly support"
    "Buy Me a Coffee" -> R.drawable.ic_coffee to "One-time tip"
    "PayPal" -> R.drawable.ic_paypal to "One-time tip"
    else -> R.drawable.ic_open_in_new to null
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
