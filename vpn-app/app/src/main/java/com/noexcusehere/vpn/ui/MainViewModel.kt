package com.noexcusehere.vpn.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.noexcusehere.vpn.R
import com.noexcusehere.vpn.UiText
import com.noexcusehere.vpn.VpnApp
import com.noexcusehere.vpn.data.ConfigResult
import com.noexcusehere.vpn.data.Profile
import com.noexcusehere.vpn.data.ProfileStore
import com.noexcusehere.vpn.data.WgConfigParser
import com.noexcusehere.vpn.uiText
import com.noexcusehere.vpn.vpn.Phase
import com.noexcusehere.vpn.vpn.VpnController
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnStatus {
    DISCONNECTED,

    /** The tunnel is being created on the phone. */
    CONNECTING,

    /** The tunnel exists, but the server has not answered yet, so no traffic is flowing. */
    WAITING,

    /** The server has answered: traffic is going through the VPN. */
    CONNECTED,
    DISCONNECTING,
}

data class ServerItem(val id: String, val name: String, val endpoint: String?)

data class HomeUiState(
    val servers: List<ServerItem> = emptyList(),
    val selectedId: String? = null,
    val status: ConnStatus = ConnStatus.DISCONNECTED,
    /** The server the tunnel is running (or starting) against, if any. */
    val activeId: String? = null,
    val uptimeSeconds: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    /** Seconds since the server last answered, or null if it never has. */
    val handshakeAgeSeconds: Long? = null,
) {
    val selected: ServerItem? get() = servers.firstOrNull { it.id == selectedId }

    /** The server the connection panel is talking about. */
    val shown: ServerItem? get() = servers.firstOrNull { it.id == activeId } ?: selected
}

/**
 * The editor screen, when open.
 *
 * @param session changes every time the editor is opened, so the screen starts from fresh text
 */
data class EditorState(
    val session: Int,
    val profileId: String?,
    val name: String,
    val text: String,
    val error: UiText? = null,
)

class MainViewModel(
    private val store: ProfileStore,
    private val controller: VpnController,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Name offered for the n-th server when the user hasn't given one. */
    private val defaultServerName: (Int) -> String = { "Server $it" },
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(store.state, controller.state, controller.snapshots()) { stored, tunnel, snapshot ->
            val stats = snapshot?.stats
            val handshake = stats?.lastHandshakeEpochMs ?: 0
            HomeUiState(
                servers = stored.profiles.map { ServerItem(it.id, it.name, it.endpoint) },
                selectedId = stored.selectedId,
                status = when (tunnel.phase) {
                    Phase.DOWN -> ConnStatus.DISCONNECTED
                    Phase.STARTING -> ConnStatus.CONNECTING
                    Phase.STOPPING -> ConnStatus.DISCONNECTING
                    Phase.UP -> if (handshake > 0) ConnStatus.CONNECTED else ConnStatus.WAITING
                },
                activeId = tunnel.profileId,
                uptimeSeconds = (snapshot?.uptimeMs ?: 0) / 1000,
                rxBytes = stats?.rxBytes ?: 0,
                txBytes = stats?.txBytes ?: 0,
                handshakeAgeSeconds = if (handshake > 0) {
                    ((wallClock() - handshake) / 1000).coerceAtLeast(0)
                } else {
                    null
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()
    private var editorSessions = 0

    private val ownMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val messages: Flow<UiText> = merge(ownMessages, controller.messages)

    fun notify(message: UiText) {
        ownMessages.tryEmit(message)
    }

    // --- Connection ---------------------------------------------------------------------

    /** Call only once Android's VPN permission has been granted. */
    fun connect() {
        store.state.value.selectedId?.let(controller::connect)
    }

    fun disconnect() {
        controller.disconnect()
    }

    fun select(id: String) {
        viewModelScope.launch {
            if (!persist { store.select(id) }) return@launch
            // Picking another server while connected means "use this one instead".
            val tunnel = controller.state.value
            val running = tunnel.phase == Phase.UP || tunnel.phase == Phase.STARTING
            if (running && tunnel.profileId != id) controller.connect(id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            if (controller.state.value.profileId == id) controller.disconnect().join()
            persist { store.delete(id) }
        }
    }

    // --- Adding and editing servers -----------------------------------------------------

    fun openNewEditor(text: String = "") {
        _editor.value = EditorState(++editorSessions, profileId = null, name = defaultName(), text = text)
    }

    fun openEditor(id: String) {
        val profile = store.state.value[id] ?: return
        _editor.value = EditorState(++editorSessions, profile.id, profile.name, profile.configText)
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun clearEditorError() {
        _editor.update { it?.copy(error = null) }
    }

    fun saveEditor(name: String, text: String) {
        val open = _editor.value ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _editor.value = open.copy(error = uiText(R.string.err_name_empty))
            return
        }
        viewModelScope.launch {
            when (val parsed = WgConfigParser.parse(text)) {
                is ConfigResult.Invalid -> _editor.update { it?.copy(error = parsed.reason) }
                is ConfigResult.Valid -> {
                    val id = open.profileId ?: newId()
                    val saved = persist {
                        store.save(Profile(id, trimmedName, parsed.text, parsed.endpoint))
                    }
                    if (!saved) return@launch
                    _editor.value = null
                    notify(savedMessage(R.string.msg_saved, trimmedName, parsed))

                    // Changes to the server in use only take effect on a fresh connection.
                    val tunnel = controller.state.value
                    if (tunnel.profileId == id && tunnel.phase == Phase.UP) {
                        controller.disconnect().join()
                        controller.connect(id)
                    }
                }
            }
        }
    }

    /** Adds a server from configuration text (a scanned QR code, or the contents of a file). */
    fun importText(text: String, suggestedName: String? = null) {
        viewModelScope.launch {
            val name = uniqueName(suggestedName?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName())
            when (val parsed = WgConfigParser.parse(text)) {
                is ConfigResult.Invalid -> {
                    if (looksLikeConfig(text)) {
                        // Nearly right: open it in the editor with the problem shown, so it can be fixed.
                        _editor.value = EditorState(++editorSessions, null, name, text.trim(), parsed.reason)
                    } else {
                        notify(uiText(R.string.msg_not_a_config))
                    }
                }
                is ConfigResult.Valid -> {
                    val profile = Profile(newId(), name, parsed.text, parsed.endpoint)
                    val saved = persist {
                        store.save(profile)
                        // Show the new server as the one to connect to, unless a VPN is running.
                        if (controller.state.value.phase == Phase.DOWN) store.select(profile.id)
                    }
                    if (saved) notify(savedMessage(R.string.msg_imported, name, parsed))
                }
            }
        }
    }

    fun importFile(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val file = withContext(io) { readConfigFile(resolver, uri) }
            when {
                file == null -> notify(uiText(R.string.msg_read_failed))
                file.text == null -> notify(uiText(R.string.msg_not_a_config))
                else -> importText(file.text, file.name)
            }
        }
    }

    // --- Helpers ------------------------------------------------------------------------

    private fun savedMessage(plain: Int, name: String, parsed: ConfigResult.Valid): UiText =
        if (parsed.ignoredKeys.isEmpty()) {
            uiText(plain, name)
        } else {
            uiText(R.string.msg_imported_ignored, name, parsed.ignoredKeys.joinToString())
        }

    /** Runs a storage change off the main thread; reports and returns false if it fails. */
    private suspend fun persist(change: () -> Unit): Boolean =
        try {
            withContext(io) { change() }
            true
        } catch (e: IOException) {
            notify(uiText(R.string.msg_save_failed))
            false
        }

    private fun defaultName(): String =
        uniqueName(defaultServerName(store.state.value.profiles.size + 1))

    private fun uniqueName(base: String): String {
        val taken = store.state.value.profiles.mapTo(HashSet()) { it.name }
        if (base !in taken) return base
        var counter = 2
        while ("$base ($counter)" in taken) counter++
        return "$base ($counter)"
    }

    private fun looksLikeConfig(text: String): Boolean =
        text.contains("[Interface]", ignoreCase = true) && text.none { it == '\u0000' }

    private class ConfigFile(val name: String?, val text: String?)

    /** Returns null if the file can't be read; a null [ConfigFile.text] if it is too big to be a config. */
    private fun readConfigFile(resolver: ContentResolver, uri: Uri): ConfigFile? =
        try {
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val name = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

            val buffer = CharArray(WgConfigParser.MAX_CONFIG_CHARS + 1)
            val length = resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                var total = 0
                while (total < buffer.size) {
                    val read = reader.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                total
            }
            when {
                length == null -> null
                length > WgConfigParser.MAX_CONFIG_CHARS -> ConfigFile(name, null)
                else -> ConfigFile(name, String(buffer, 0, length))
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as VpnApp
                MainViewModel(
                    store = app.store,
                    controller = app.controller,
                    defaultServerName = { app.getString(R.string.default_server_name, it) },
                )
            }
        }
    }
}
