package com.noexcusehere.firestickremote

import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Talks to a Fire TV over ADB (Wi-Fi, port 5555). Same commands as server.py. */
class FireTv(filesDir: File) {

    companion object {
        val KEYS = mapOf(
            "up" to 19, "down" to 20, "left" to 21, "right" to 22, "select" to 23,
            "back" to 4, "home" to 3, "menu" to 82,
            "playpause" to 85, "rewind" to 89, "fastforward" to 90,
            "next" to 87, "previous" to 88,
            "volup" to 24, "voldown" to 25, "mute" to 164,
            "sleep" to 223, "wakeup" to 224, "power" to 26,
            "delete" to 67, "enter" to 66, "search" to 84,
        )

        val APPS = linkedMapOf(
            "Netflix" to "com.netflix.ninja",
            "YouTube" to "com.amazon.firetv.youtube",
            "Prime Video" to "com.amazon.avod",
            "Disney+" to "com.disney.disneyplus",
            "Stan" to "au.com.stan.and",
            "Binge" to "au.com.streamotion.ares.tv",
            "ABC iview" to "au.net.abc.iview",
            "SBS On Demand" to "com.sbs.ondemand.tv",
            "9Now" to "au.com.nine.jumpin.android",
            "7plus" to "au.com.seven.plus",
            "10 Play" to "au.com.tenplay",
            "Kayo" to "au.com.kayosports.tv",
            "Spotify" to "com.spotify.tv.android",
            "Plex" to "com.plexapp.android",
            "Kodi" to "org.xbmc.kodi",
        )

        fun shellQuote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
    }

    private val keyPair: AdbKeyPair
    private var dadb: Dadb? = null
    var target: String? = null
        private set

    // Commands are launched in order on one thread; their output is drained on others.
    private val sendQueue = Executors.newSingleThreadExecutor()
    private val drainPool = Executors.newCachedThreadPool()

    init {
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        keyPair = AdbKeyPair.read(priv, pub)
    }

    private fun parse(host: String): Pair<String, Int> {
        val h = host.trim()
        val i = h.lastIndexOf(':')
        return if (i > 0) h.substring(0, i) to (h.substring(i + 1).toIntOrNull() ?: 5555) else h to 5555
    }

    @Synchronized
    private fun open(host: String): Dadb {
        dadb?.let { runCatching { it.close() } }
        val (ip, port) = parse(host)
        // 5 s to reach the TV; up to 60 s waiting for "Allow" to be pressed on first use.
        return Dadb.create(ip, port, keyPair, 5_000, 60_000).also { dadb = it }
    }

    /** Runs a command and returns its output (blocks). */
    fun query(cmd: String): String {
        val host = target ?: throw IllegalStateException("Not connected to a Firestick")
        val d = dadb ?: open(host)
        return try {
            d.open("shell:$cmd").use { it.source.readUtf8() }
        } catch (e: Exception) {
            // Connection dropped (e.g. TV slept): reconnect once.
            open(host).open("shell:$cmd").use { it.source.readUtf8() }
        }
    }

    /** Fire-and-forget command, kept in order so key presses arrive in sequence. */
    fun send(cmd: String) {
        val host = target ?: throw IllegalStateException("Not connected to a Firestick")
        sendQueue.execute {
            val stream = try {
                (dadb ?: open(host)).open("shell:$cmd")
            } catch (e: Exception) {
                runCatching { open(host).open("shell:$cmd") }.getOrNull()
            } ?: return@execute
            drainPool.execute { runCatching { stream.use { it.source.readUtf8() } } }
        }
    }

    fun connect(host: String): Pair<Boolean, String> {
        val (ip, port) = parse(host)
        if (ip.isEmpty()) return false to "Enter the Firestick's IP address"
        // Quick reachability check gives a clearer error than ADB does.
        try {
            Socket().use { it.connect(InetSocketAddress(ip, port), 4_000) }
        } catch (e: Exception) {
            return false to "Can't reach $ip:$port. Check the IP, that you're on the same Wi-Fi, and that ADB debugging is ON."
        }
        return try {
            val out = open("$ip:$port").open("shell:echo ok").use { it.source.readUtf8() }
            if (out.contains("ok")) {
                target = "$ip:$port"
                true to "Connected"
            } else false to "Unexpected reply: $out"
        } catch (e: Exception) {
            false to "The Firestick didn't accept the connection. If the TV showed \"Allow USB debugging?\", choose Allow (tick Always allow) and try again."
        }
    }

    fun isConnected(): Boolean =
        target != null && runCatching { query("echo ok").contains("ok") }.getOrDefault(false)

    fun scan(port: Int = 5555): List<String> {
        val myIp = localIp() ?: return emptyList()
        val prefix = myIp.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(64)
        try {
            val tasks = (1..254).map { n ->
                Callable {
                    val ip = "$prefix.$n"
                    runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 400) }; ip }.getOrNull()
                }
            }
            return pool.invokeAll(tasks).mapNotNull { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun localIp(): String? {
        val addrs = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
        return addrs.firstOrNull()?.hostAddress
    }
}
