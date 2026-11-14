package com.noexcusehere.vpn.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.noexcusehere.vpn.R
import com.noexcusehere.vpn.uiText

/** Connects the screens to the view model and to the Android pieces that need an Activity. */
@Composable
fun AppRoot(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val qrPrompt = stringResource(R.string.qr_prompt)

    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.showSnackbar(it.resolve(context)) }
    }

    // Android shows its own "Connection request" dialog the first time; we only connect if
    // the user accepts it.
    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connect()
        } else {
            viewModel.notify(uiText(R.string.msg_permission_denied))
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(context.contentResolver, uri)
    }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.importText(it) }
    }

    fun requestConnect() {
        val permissionIntent = try {
            VpnService.prepare(context)
        } catch (e: RuntimeException) {
            viewModel.notify(uiText(R.string.msg_vpn_unavailable))
            return
        }
        if (permissionIntent == null) {
            viewModel.connect()
            return
        }
        try {
            vpnPermission.launch(permissionIntent)
        } catch (e: ActivityNotFoundException) {
            viewModel.notify(uiText(R.string.msg_vpn_unavailable))
        }
    }

    val open = editor
    if (open != null) {
        EditorScreen(
            editor = open,
            onSave = viewModel::saveEditor,
            onEdited = viewModel::clearEditorError,
            onClose = viewModel::closeEditor,
            onMessage = viewModel::notify,
            snackbarHost = { SnackbarHost(snackbars) },
        )
    } else {
        HomeScreen(
            state = state,
            onToggle = {
                if (state.status == ConnStatus.DISCONNECTED) requestConnect() else viewModel.disconnect()
            },
            onSelect = viewModel::select,
            onEdit = viewModel::openEditor,
            onDelete = viewModel::delete,
            onImportFile = {
                // Config files have no reliable MIME type, so every file is offered.
                try {
                    filePicker.launch(arrayOf("*/*"))
                } catch (e: ActivityNotFoundException) {
                    viewModel.notify(uiText(R.string.msg_read_failed))
                }
            },
            onScanQr = {
                qrScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(qrPrompt)
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            onPaste = { viewModel.openNewEditor() },
            onOpenVpnSettings = {
                try {
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    viewModel.notify(uiText(R.string.msg_settings_unavailable))
                }
            },
            snackbarHost = { SnackbarHost(snackbars) },
        )
    }
}
