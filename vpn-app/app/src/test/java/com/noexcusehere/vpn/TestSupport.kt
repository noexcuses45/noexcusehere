package com.noexcusehere.vpn

import com.noexcusehere.vpn.vpn.TunnelBackend
import com.noexcusehere.vpn.vpn.TunnelStats
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import java.util.concurrent.CountDownLatch

/** A syntactically valid client configuration with freshly generated keys. */
fun validConfig(endpoint: String = "vpn.example.com:51820", extraInterfaceLine: String = ""): String =
    listOf(
        "[Interface]",
        "PrivateKey = ${KeyPair().privateKey.toBase64()}",
        "Address = 10.8.0.2/32",
        "DNS = 1.1.1.1",
        extraInterfaceLine,
        "",
        "[Peer]",
        "PublicKey = ${KeyPair().publicKey.toBase64()}",
        "AllowedIPs = 0.0.0.0/0, ::/0",
        "Endpoint = $endpoint",
    ).joinToString("\n")

/** Stands in for the real WireGuard engine, which needs a phone. */
class FakeBackend : TunnelBackend {
    @Volatile var failWith: Exception? = null
    @Volatile var stats = TunnelStats()
    @Volatile var isUp = false
        private set
    @Volatile var downCalls = 0
        private set
    val sessions = mutableListOf<String>()

    /** When set, [up] blocks until the latch is released, like a slow DNS lookup. */
    @Volatile var holdUp: CountDownLatch? = null
    val upEntered = CountDownLatch(1)

    @Volatile private var onStoppedBySystem: (() -> Unit)? = null

    override fun up(sessionName: String, config: Config, onStoppedBySystem: () -> Unit) {
        upEntered.countDown()
        holdUp?.await()
        failWith?.let { throw it }
        sessions += sessionName
        isUp = true
        this.onStoppedBySystem = onStoppedBySystem
    }

    override fun down() {
        downCalls++
        isUp = false
        onStoppedBySystem = null
    }

    override fun stats(): TunnelStats? = if (isUp) stats else null

    /** Simulates Android revoking the VPN, e.g. because another VPN app connected. */
    fun stopBySystem() {
        isUp = false
        onStoppedBySystem?.invoke()
    }
}
