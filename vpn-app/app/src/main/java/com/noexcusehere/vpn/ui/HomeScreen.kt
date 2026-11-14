package com.noexcusehere.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.vpn.R

/** How long to wait for the server before suggesting something is wrong. */
private const val NO_REPLY_HINT_AFTER_SECONDS = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImportFile: () -> Unit,
    onScanQr: () -> Unit,
    onPaste: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
) {
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerItem?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = snackbarHost,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "connection") {
                ConnectionPanel(state, onToggle)
            }
            if (state.status == ConnStatus.CONNECTED || state.status == ConnStatus.WAITING) {
                item(key = "stats") {
                    StatsRow(state)
                }
            }
            if (state.servers.isEmpty()) {
                item(key = "empty") {
                    EmptyState(onImportFile, onScanQr, onPaste)
                }
            } else {
                item(key = "servers-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.servers_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() },
                        )
                        FilledTonalButton(onClick = { showAddSheet = true }) {
                            Icon(
                                painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_add))
                        }
                    }
                }
                items(state.servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        selected = server.id == state.selectedId,
                        inUse = server.id == state.activeId && state.status == ConnStatus.CONNECTED,
                        onSelect = { onSelect(server.id) },
                        onEdit = { onEdit(server.id) },
                        onDelete = { pendingDelete = server },
                    )
                }
                item(key = "always-on") {
                    AlwaysOnCard(onOpenVpnSettings)
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                text = stringResource(R.string.add_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            AddOptions(
                onImportFile = { showAddSheet = false; onImportFile() },
                onScanQr = { showAddSheet = false; onScanQr() },
                onPaste = { showAddSheet = false; onPaste() },
                containerColor = Color.Transparent,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title, server.name)) },
            text = { Text(stringResource(R.string.delete_body)) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(server.id) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConnectionPanel(state: HomeUiState, onToggle: () -> Unit) {
    val server = state.shown
    val title = stringResource(
        when (state.status) {
            ConnStatus.DISCONNECTED -> R.string.status_disconnected
            ConnStatus.CONNECTING, ConnStatus.WAITING -> R.string.status_connecting
            ConnStatus.CONNECTED -> R.string.status_connected
            ConnStatus.DISCONNECTING -> R.string.status_disconnecting
        },
    )
    val hint = when {
        server == null -> stringResource(R.string.hint_no_servers)
        else -> when (state.status) {
            ConnStatus.DISCONNECTED -> stringResource(R.string.hint_tap_to_connect, server.name)
            ConnStatus.CONNECTING -> stringResource(R.string.hint_starting, server.name)
            ConnStatus.WAITING ->
                if (state.uptimeSeconds >= NO_REPLY_HINT_AFTER_SECONDS) {
                    stringResource(R.string.hint_no_reply, server.name)
                } else {
                    stringResource(R.string.hint_waiting, server.name)
                }
            ConnStatus.CONNECTED ->
                stringResource(R.string.hint_connected, server.name, formatDuration(state.uptimeSeconds))
            ConnStatus.DISCONNECTING -> server.name
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PowerButton(
            status = state.status,
            // While the tunnel is being torn down there is nothing useful a tap could do.
            enabled = server != null && state.status != ConnStatus.DISCONNECTING,
            onClick = onToggle,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (state.status == ConnStatus.CONNECTED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun StatsRow(state: HomeUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(
            icon = R.drawable.ic_download,
            label = stringResource(R.string.stat_download),
            value = formatBytes(state.rxBytes),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            icon = R.drawable.ic_upload,
            label = stringResource(R.string.stat_upload),
            value = formatBytes(state.txBytes),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            icon = R.drawable.ic_clock,
            label = stringResource(R.string.stat_handshake),
            value = handshakeAge(state.handshakeAgeSeconds),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun handshakeAge(seconds: Long?): String = when {
    seconds == null -> stringResource(R.string.age_none)
    seconds < 5 -> stringResource(R.string.age_just_now)
    seconds < 60 -> stringResource(R.string.age_seconds, seconds)
    seconds < 3600 -> stringResource(R.string.age_minutes, seconds / 60)
    else -> stringResource(R.string.age_hours, seconds / 3600)
}

@Composable
private fun StatCard(icon: Int, label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // Label and value are read together by screen readers ("Download, 1.2 MB").
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerItem,
    selected: Boolean,
    inUse: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.endpoint ?: stringResource(R.string.server_no_endpoint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (inUse) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = stringResource(R.string.server_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.server_options, server.name),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onImportFile: () -> Unit, onScanQr: () -> Unit, onPaste: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                text = stringResource(R.string.empty_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            AddOptions(
                onImportFile = onImportFile,
                onScanQr = onScanQr,
                onPaste = onPaste,
                containerColor = Color.Transparent,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** The three ways to add a server; shown in the empty state and in the "Add" sheet. */
@Composable
private fun AddOptions(
    onImportFile: () -> Unit,
    onScanQr: () -> Unit,
    onPaste: () -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        AddOption(R.drawable.ic_file, R.string.add_file, R.string.add_file_desc, containerColor, onImportFile)
        AddOption(R.drawable.ic_qr, R.string.add_qr, R.string.add_qr_desc, containerColor, onScanQr)
        AddOption(R.drawable.ic_paste, R.string.add_paste, R.string.add_paste_desc, containerColor, onPaste)
    }
}

@Composable
private fun AddOption(icon: Int, title: Int, description: Int, containerColor: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
        leadingContent = {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier = Modifier
            .clip(shape)
            .selectable(selected = false, role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun AlwaysOnCard(onOpenVpnSettings: () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.always_on_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.always_on_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onOpenVpnSettings,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.always_on_action))
            }
        }
    }
}
