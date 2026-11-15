package com.noexcusehere.mashwave.iptv

import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Fetches things from a TV service: follows forwarding addresses and explains failures in plain words. */
object TvNet {
    /** How the app introduces itself to servers. Set once at start-up with the real Android version. */
    @Volatile
    var userAgent: String = "UltimateBeats/1.0 (Linux;Android) AndroidXMedia3/1.9.4"

    class Response(val connection: HttpURLConnection, val url: String) : Closeable {
        val code: Int
            get() = connection.responseCode

        val stream: InputStream
            get() = connection.inputStream

        fun header(name: String): String? = connection.getHeaderField(name)

        override fun close() {
            runCatching { connection.inputStream.close() }
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Opens [address]. Services often answer "it's over there" several times in a row, sometimes
     * hopping between http and https, so those hops are followed here by hand.
     * With [check], anything but a success answer is turned into a [TvException].
     */
    fun open(
        address: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 15_000,
        readMs: Int = 30_000,
        check: Boolean = true,
    ): Response {
        var url = address
        var hops = 0
        try {
            while (true) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = connectMs
                connection.readTimeout = readMs
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "*/*")
                for ((name, value) in headers) connection.setRequestProperty(name, value)
                val code = connection.responseCode
                if (code in 300..399 && code != 304) {
                    val next = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (next.isNullOrBlank() || ++hops > 8) throw TvException("The service kept sending us somewhere else.")
                    url = URL(URL(url), next).toString()
                    continue
                }
                if (check && code !in 200..299) {
                    connection.disconnect()
                    throw TvException(explain(code))
                }
                return Response(connection, url)
            }
        } catch (e: TvException) {
            throw e
        } catch (e: Exception) {
            val trouble = classify(e)
            if (trouble == Trouble.OTHER) throw TvException(explain(e), e)
            // Say which server could not be reached and where this network sent the app for it:
            // "refused" and "blocked on this Wi-Fi" need different fixes.
            val host = runCatching { URL(url).host }.getOrDefault("")
            val port = runCatching { URL(url).let { if (it.port > 0) it.port else it.defaultPort } }.getOrDefault(80)
            val found = if (trouble == Trouble.NO_NAME) emptyList() else lookup(host)
            throw TvException(describe(trouble, host, port, found, forwarded = hops > 0), e, network = true)
        }
    }

    /** The ways a connection can fail before the server says anything. */
    enum class Trouble { REFUSED, UNREACHABLE, TIMED_OUT, NO_NAME, SECURE, OTHER }

    fun classify(e: Throwable): Trouble {
        val text = generateSequence(e) { it.cause }.take(4).joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            e is UnknownHostException -> Trouble.NO_NAME
            e is SSLException -> Trouble.SECURE
            "econnrefused" in text || "connection refused" in text -> Trouble.REFUSED
            "enetunreach" in text || "ehostunreach" in text || "unreachable" in text || "no route" in text -> Trouble.UNREACHABLE
            e is SocketTimeoutException || "etimedout" in text || "timed out" in text || "timeout" in text -> Trouble.TIMED_OUT
            e is ConnectException -> Trouble.REFUSED
            else -> Trouble.OTHER
        }
    }

    /** The addresses this phone's network gives for [host]; empty when it gives none. */
    fun lookup(host: String): List<String> =
        runCatching { java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }.getOrDefault(emptyList())

    /** An answer that leads nowhere - what ad blockers and filtering networks hand out for addresses they block. */
    fun isDeadEnd(address: String): Boolean =
        address == "0.0.0.0" || address.startsWith("127.") || address == "::" || address == "::1" || address == "0:0:0:0:0:0:0:0" || address == "0:0:0:0:0:0:0:1"

    /** A connection failure in plain words, with the detail needed to tell "wrong address" from "blocked here". */
    fun describe(trouble: Trouble, host: String, port: Int, found: List<String>, forwarded: Boolean = false): String {
        val where = if (forwarded) "$host (where the service forwarded the app)" else host
        val first = found.firstOrNull().orEmpty()
        val another = "Try the other DNS your TV provider gave you, or switch between Wi-Fi and mobile data."
        return when {
            trouble == Trouble.NO_NAME ->
                "Can't find $where. Check the spelling and the internet connection. If both are right, this network may be hiding that address. $another"
            found.isNotEmpty() && found.all { isDeadEnd(it) } ->
                "This network sends the app to a dead end for $where (it answered \"$first\"). Something on this Wi-Fi or phone is blocking that address: " +
                    "an ad blocker, a \"Private DNS\" setting, a VPN, or the internet provider. $another"
            trouble == Trouble.REFUSED ->
                "$where refused the connection. The address may be out of date, or this network blocks it. $another (Refused by $first, port $port)"
            trouble == Trouble.UNREACHABLE ->
                "This network can't get to $where. Check the Wi-Fi, and any VPN on the phone. $another" + if (first.isNotEmpty()) " (No route to $first)" else ""
            trouble == Trouble.TIMED_OUT ->
                "$where did not answer. It may be down, or this network blocks it. $another (No answer from $first, port $port)"
            trouble == Trouble.SECURE ->
                "The secure connection to $where failed. If the address starts with https://, try http://."
            else -> "Couldn't reach $where."
        }
    }

    fun explain(code: Int): String = when (code) {
        401, 403 -> "The service refused this login."
        404 -> "Nothing was found at that address."
        407 -> "The network needs a sign-in before it lets the app out."
        429 -> "The service says it is being asked too often. Wait a minute and try again."
        in 500..599 -> "The service is having trouble right now (error $code)."
        else -> "The service answered with error $code."
    }

    fun explain(e: Throwable): String = when (e) {
        is TvException -> e.message ?: "Something went wrong."
        is UnknownHostException -> "Can't find that server. Check the address and the internet connection."
        is SocketTimeoutException -> "The server took too long to answer."
        is ConnectException -> "Couldn't connect to the server. Check the address."
        is SSLException -> "The secure connection failed. If the address starts with https://, try http://."
        is FileNotFoundException -> "Nothing was found at that address."
        is java.net.MalformedURLException -> "That doesn't look like a web address."
        is kotlinx.serialization.SerializationException -> "The server answered, but not like a TV service."
        else -> "Couldn't reach the service."
    }
}
