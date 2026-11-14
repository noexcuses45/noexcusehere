package com.noexcusehere.vpn.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

data class TunnelStats(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    /** Wall-clock time of the most recent handshake with the server, or 0 if there has been none. */
    val lastHandshakeEpochMs: Long = 0,
)

/** Thrown when Android tears the VPN down while it is still being brought up. */
class TunnelStoppedException : Exception("The VPN was stopped while starting")

/**
 * The part of the app that actually moves packets. Kept behind an interface so everything
 * above it can be tested without a phone.
 *
 * [up] and [down] block and must not be called on the main thread.
 */
interface TunnelBackend {
    /**
     * Brings the tunnel up, replacing any tunnel that is already running.
     *
     * @param onStoppedBySystem called (on the main thread) if Android later stops the VPN on
     * its own, for example because another VPN app took over. Not called for [down].
     */
    @Throws(Exception::class)
    fun up(sessionName: String, config: Config, onStoppedBySystem: () -> Unit)

    @Throws(Exception::class)
    fun down()

    /** Traffic counters for the running tunnel, or null when nothing is running. */
    fun stats(): TunnelStats?
}

/** [TunnelBackend] backed by the official WireGuard userspace implementation. */
class WireGuardBackend(context: Context) : TunnelBackend {

    private val appContext = context.applicationContext

    // Created on first use: constructing it loads the native WireGuard library.
    private val backend: GoBackend by lazy { GoBackend(appContext) }

    @Volatile
    private var current: SessionTunnel? = null

    @Synchronized
    override fun up(sessionName: String, config: Config, onStoppedBySystem: () -> Unit) {
        // Stop any running tunnel ourselves rather than letting the library swap it: on a
        // failed start the library would quietly bring the old tunnel back.
        down()

        val tunnel = SessionTunnel(sessionName)
        backend.setState(tunnel, Tunnel.State.UP, config)
        current = tunnel

        // Listen for the system stopping the VPN only once it is up, then confirm it still
        // is, so a stop that raced with start-up is not missed.
        tunnel.onDown = {
            if (current === tunnel) current = null
            onStoppedBySystem()
        }
        if (backend.getState(tunnel) != Tunnel.State.UP) {
            tunnel.onDown = null
            if (current === tunnel) current = null
            throw TunnelStoppedException()
        }
    }

    @Synchronized
    override fun down() {
        val tunnel = current ?: return
        tunnel.onDown = null
        current = null
        backend.setState(tunnel, Tunnel.State.DOWN, null)
    }

    override fun stats(): TunnelStats? {
        val tunnel = current ?: return null
        val statistics = backend.getStatistics(tunnel)
        var lastHandshake = 0L
        for (peer in statistics.peers()) {
            val peerStats = statistics.peer(peer) ?: continue
            lastHandshake = maxOf(lastHandshake, peerStats.latestHandshakeEpochMillis())
        }
        return TunnelStats(statistics.totalRx(), statistics.totalTx(), lastHandshake)
    }

    private class SessionTunnel(private val name: String) : Tunnel {
        @Volatile
        var onDown: (() -> Unit)? = null

        override fun getName(): String = name

        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) onDown?.invoke()
        }
    }
}
