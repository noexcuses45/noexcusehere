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
            throw TvException(explain(e), e)
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
