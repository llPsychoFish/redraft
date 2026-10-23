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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.redraft.R
import kotlinx.coroutines.launch

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
    val linkErrorMsg = stringResource(R.string.support_link_error)
    val moneroAddressLabel = stringResource(R.string.support_monero_address)
    val addressCopiedMsg = stringResource(R.string.support_address_copied)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.support_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
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
                        text = stringResource(R.string.support_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.support_ways_to_support)) }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    if (links.isEmpty()) {
                        Text(
                            text = stringResource(R.string.support_no_links),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    } else {
                        links.forEach { link ->
                            val (iconRes, subtitleRes) = supportPresentation(link.label)
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!openExternalLink(context, link.url)) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(linkErrorMsg)
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
                                supportingContent = subtitleRes?.let { res ->
                                    {
                                        Text(
                                            text = stringResource(res),
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
                item { SectionHeader(stringResource(R.string.support_crypto)) }
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
                                                    moneroAddressLabel,
                                                    SupportLinks.XMR_ADDRESS,
                                                ),
                                            ),
                                        )
                                        snackbarHostState.showSnackbar(addressCopiedMsg)
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
                                    text = stringResource(R.string.support_monero),
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
private fun supportPresentation(label: String): Pair<Int, Int?> = when (label) {
    "GitHub Sponsors" -> R.drawable.ic_heart to R.string.support_subtitle_recurring
    "Buy Me a Coffee" -> R.drawable.ic_coffee to R.string.support_subtitle_one_time
    "PayPal" -> R.drawable.ic_paypal to R.string.support_subtitle_one_time
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
