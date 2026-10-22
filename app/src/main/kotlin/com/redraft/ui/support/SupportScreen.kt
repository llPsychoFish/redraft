package com.redraft.ui.support

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
        ) {
            item {
                Text(
                    text = SUPPORT_MESSAGE,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
            if (links.isEmpty()) {
                item {
                    Text(
                        text = NO_LINKS,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(count = links.size) { index ->
                    val link = links[index]
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!openExternalLink(context, link.url)) {
                                    scope.launch { snackbarHostState.showSnackbar(LINK_ERROR) }
                                }
                            },
                        headlineContent = { Text(link.label) },
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
}
