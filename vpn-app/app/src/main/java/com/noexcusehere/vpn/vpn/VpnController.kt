package com.noexcusehere.vpn.vpn

import android.os.SystemClock
import android.util.Log
import com.noexcusehere.vpn.R
import com.noexcusehere.vpn.UiText
import com.noexcusehere.vpn.data.ConfigResult
import com.noexcusehere.vpn.data.ProfileStore
import com.noexcusehere.vpn.data.WgConfigParser
import com.noexcusehere.vpn.uiText
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.BackendException.Reason
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

enum class Phase { DOWN, STARTING, UP, STOPPING }

data class TunnelState(
    val phase: Phase = Phase.DOWN,
    val profileId: String? = null,
    /** [SystemClock.elapsedRealtime] at which the tunnel came up; only meaningful while UP. */
    val upSince: Long = 0,
)

data class TunnelSnapshot(val uptimeMs: Long, val stats: TunnelStats)

/**
 * Owns the VPN connection for the whole app process. Lives in the Application rather than
 * a screen, because the tunnel keeps running when the UI is closed.
 *
 * All public functions must be called on the main thread; the blocking work is moved to [io].
 */
class VpnController(
    private val backend: TunnelBackend,
    private val store: ProfileStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    /** Pause after stopping, giving Android time to finish tearing the old VPN down. */
    private val settleMs: Long = 400,
) {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)

    /** Problems worth telling the user about. Dropped if no screen is listening. */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    /** The most recent connect/disconnect request; a newer request supersedes it. */
    private var request: Job? = null

    /** Connects to the given saved server, switching away from any other one. */
    fun connect(profileId: String): Job = replaceRequest { connectNow(profileId) }

    /** Connects to whichever server is selected; used when Android starts the VPN itself. */
    fun connectSelected() {
        store.state.value.selectedId?.let(::connect)
    }

    /** Disconnects, or abandons a connection attempt that is still in progress. */
    fun disconnect(): Job = replaceRequest { disconnectNow() }

    /**
     * Uptime and traffic counters once a second while connected, and null otherwise.
     * Nothing is polled unless something is collecting this flow.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(periodMs: Long = 1_000): Flow<TunnelSnapshot?> =
        state
            .map { if (it.phase == Phase.UP) it.upSince else null }
            .distinctUntilChanged()
            .flatMapLatest { upSince ->
                if (upSince == null) {
                    flowOf(null)
                } else {
                    flow {
                        while (true) {
                            val stats = withContext(io) { runCatching { backend.stats() }.getOrNull() }
                            emit(TunnelSnapshot(elapsedRealtime() - upSince, stats ?: TunnelStats()))
                            delay(periodMs)
                        }
                    }
                }
            }

    private fun replaceRequest(block: suspend () -> Unit): Job {
        val previous = request
        val job = scope.launch {
            // Cancelling interrupts a start that is stuck (e.g. waiting on DNS) and waits for
            // it to clean up, so requests never overlap.
            previous?.cancelAndJoin()
            block()
        }
        request = job
        return job
    }

    private suspend fun connectNow(profileId: String) {
        val current = _state.value
        if (current.profileId == profileId && current.phase == Phase.UP) return

        val profile = store.state.value[profileId] ?: return
        val config = when (val parsed = WgConfigParser.parse(profile.configText)) {
            is ConfigResult.Valid -> parsed.config
            is ConfigResult.Invalid -> {
                _messages.tryEmit(parsed.reason)
                return
            }
        }

        disconnectNow()
        _state.value = TunnelState(Phase.STARTING, profileId)
        try {
            runInterruptible(io) {
                backend.up(WgConfigParser.sessionName(profile.name), config) { onStoppedBySystem(profileId) }
            }
            // If Android already stopped the tunnel again, the state is DOWN and stays that way.
            _state.update { state ->
                if (state.phase == Phase.STARTING && state.profileId == profileId) {
                    TunnelState(Phase.UP, profileId, elapsedRealtime())
                } else {
                    state
                }
            }
        } catch (e: CancellationException) {
            // Superseded by a newer request. The tunnel may have come up just before the
            // cancellation landed, so make sure it is down before handing over.
            stopBackend()
            _state.value = TunnelState()
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Could not bring the tunnel up", e)
            stopBackend()
            _state.value = TunnelState()
            _messages.tryEmit(describe(e))
        }
    }

    private suspend fun disconnectNow() {
        val current = _state.value
        if (current.phase == Phase.DOWN) return
        _state.value = current.copy(phase = Phase.STOPPING)
        // Once started, a stop always runs to completion, even if another request arrives.
        withContext(NonCancellable) {
            stopBackend()
            delay(settleMs)
            _state.value = TunnelState()
        }
    }

    private suspend fun stopBackend() {
        withContext(NonCancellable + io) {
            try {
                backend.down()
            } catch (e: Throwable) {
                Log.w(TAG, "Could not bring the tunnel down cleanly", e)
            }
        }
    }

    /** Android stopped the VPN on its own (another VPN app took over, or it was revoked). */
    private fun onStoppedBySystem(profileId: String) {
        var wasUp = false
        _state.update { state ->
            if (state.profileId == profileId && (state.phase == Phase.UP || state.phase == Phase.STARTING)) {
                wasUp = state.phase == Phase.UP
                TunnelState()
            } else {
                state
            }
        }
        if (wasUp) _messages.tryEmit(uiText(R.string.msg_revoked))
    }

    private fun describe(e: Throwable): UiText = when (e) {
        is BackendException -> {
            val detail = e.format.firstOrNull()?.toString().orEmpty()
            when (e.reason) {
                Reason.DNS_RESOLUTION_FAILURE -> uiText(R.string.err_dns, detail)
                Reason.VPN_NOT_AUTHORIZED -> uiText(R.string.err_not_authorized)
                Reason.UNABLE_TO_START_VPN -> uiText(R.string.err_start_failed)
                Reason.TUN_CREATION_ERROR -> uiText(R.string.err_tun_failed)
                Reason.GO_ACTIVATION_ERROR_CODE -> uiText(R.string.err_engine_failed, detail)
                else -> uiText(R.string.err_connect_generic, e.reason.name)
            }
        }
        is TunnelStoppedException -> uiText(R.string.err_stopped_early)
        else -> uiText(R.string.err_connect_generic, e.message ?: e.javaClass.simpleName)
    }

    private companion object {
        const val TAG = "KestrelVpn"
    }
}
