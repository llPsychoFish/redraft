package com.redraft.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditingSelfTestScreen(
    viewModel: EditingSelfTestViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context: Context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val report by viewModel.report.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val copyRequested by viewModel.copyRequested.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(copyRequested) {
        if (copyRequested) {
            val text = report?.toPlainText() ?: ""
            clipboard.setPrimaryClip(ClipData.newPlainText("self-test-report", text))
            scope.launch { snackbarHostState.showSnackbar("Report copied to clipboard") }
            viewModel.copyAcknowledged()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_editing_self_test)) },
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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Text(
                text = stringResource(R.string.settings_editing_self_test_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            report?.let { r ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = "ABI: ${r.abi}  |  Android SDK: ${r.androidVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(
                    items = report?.steps ?: emptyList(),
                    key = { it.name },
                ) { step ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { /* no-op: steps run via the button */ },
                        leadingContent = {
                            val color = when (step.status) {
                                Status.OK -> MaterialTheme.colorScheme.primary
                                Status.FAIL -> MaterialTheme.colorScheme.error
                                Status.RUNNING, Status.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                        },
                        headlineContent = {
                            Text(
                                text = step.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        supportingContent = {
                            step.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                    )
                }
            }

            Button(
                onClick = { viewModel.runSelfTest() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = if (running) stringResource(R.string.settings_editing_self_test_running)
                    else stringResource(R.string.settings_editing_self_test_run),
                )
            }

            report?.let { _ ->
                Button(
                    onClick = { viewModel.requestCopy() },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Text(text = stringResource(R.string.settings_editing_self_test_copy_report))
                }
            }
        }
    }
}
