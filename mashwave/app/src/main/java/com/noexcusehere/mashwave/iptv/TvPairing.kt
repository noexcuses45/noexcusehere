package com.noexcusehere.mashwave.iptv

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Typing a server address, a user name and a password with a TV remote is miserable. While the
 * sign-in screen is open on the TV, this serves one small web page on the home network: the
 * viewer opens it on their phone or computer, pastes the message from their provider, and the
 * TV signs in. The page only accepts the [code] shown on the TV, and stops existing when the
 * screen is closed.
 */
class TvPairing(
    val code: String,
    /** Called with a sign-in to try. Returns null when it worked, or a sentence saying why not. */
    private val signIn: (TvAccount, List<String>) -> String?,
) : AutoCloseable {
    private val socket: ServerSocket = open()
    private var wrongCodes = 0

    val port: Int
        get() = socket.localPort

    init {
        thread(isDaemon = true, name = "tv-pairing") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true, name = "tv-pairing-request") { runCatching { client.use { answer(it) } } }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun answer(client: Socket) {
        client.soTimeout = 20_000
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val first = lines.first().split(' ')
        if (first.size < 2) return
        val method = first[0].uppercase()
        val path = first[1].substringBefore('?')
        var length = 0
        for (line in lines.drop(1)) {
            if (line.startsWith("content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val page = when {
            method == "GET" && path == "/" -> form(null, null)
            method == "POST" && path == "/send" && length in 1..MAX_BODY -> {
                val body = ByteArray(length)
                var got = 0
                while (got < length) {
                    val n = input.read(body, got, length - got)
                    if (n < 0) break
                    got += n
                }
                receive(fields(String(body, 0, got, Charsets.UTF_8)))
            }
            else -> null
        }
        val out = client.getOutputStream()
        if (page == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        } else {
            val bytes = page.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1),
            )
            out.write(bytes)
        }
        out.flush()
    }

    private fun receive(fields: Map<String, String>): String {
        if (wrongCodes >= MAX_WRONG_CODES) return form("Too many wrong codes. Close the sign-in screen on the TV and open it again for a new one.", null)
        if (fields["code"].orEmpty().trim() != code) {
            wrongCodes++
            return form("That is not the code on the TV screen.", fields)
        }
        val (account, spares) = accountFrom(fields) ?: return form(
            "That doesn't look like TV sign-in details. Paste the whole message from your provider, or fill in the three boxes underneath.",
            fields,
        )
        val problem = runCatching { signIn(account, spares) }.getOrElse { "Something went wrong on the TV. Try again." }
        return if (problem == null) done() else form(problem, fields)
    }

    private fun readHead(input: java.io.InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return buffer.toString("ISO-8859-1").dropLast(4)
        }
        return null
    }

    private fun form(problem: String?, kept: Map<String, String>?): String {
        fun kept(name: String) = escape(kept?.get(name).orEmpty())
        val note = if (problem == null) "" else "<p class=\"bad\">${escape(problem)}</p>"
        return PAGE_START + """
<h1>Sign in on your TV</h1>
<p>Type the code your TV is showing, then paste the message your TV provider sent you (the one with the address, user name and password). A playlist link works too.</p>
$note
<form method="post" action="/send">
<label>Code on the TV</label>
<input name="code" inputmode="numeric" autocomplete="off" maxlength="8" value="${kept("code")}" required>
<label>Paste your provider's message here</label>
<textarea name="text" rows="6" placeholder="Paste here">${kept("text")}</textarea>
<p class="or">or fill these in instead</p>
<label>Server address</label>
<input name="server" autocapitalize="off" autocorrect="off" placeholder="http://example.com:8080" value="${kept("server")}">
<label>User name</label>
<input name="username" autocapitalize="off" autocorrect="off" value="${kept("username")}">
<label>Password</label>
<input name="password" autocapitalize="off" autocorrect="off" value="${kept("password")}">
<button type="submit">Send to the TV</button>
</form>
<p class="small">This page is on your own Wi-Fi and goes straight to your TV. Nothing is sent anywhere else.</p>
""" + PAGE_END
    }

    private fun done(): String = PAGE_START + "<h1>Done</h1><p>Your TV is signed in. You can close this page and pick up the remote.</p>" + PAGE_END

    companion object {
        private const val MAX_BODY = 64 * 1024
        private const val MAX_WRONG_CODES = 10
        private const val FIRST_PORT = 8617

        /** A fresh four-digit code. */
        fun newCode(): String = (1000 + java.security.SecureRandom().nextInt(9000)).toString()

        private fun open(): ServerSocket {
            // An easy number to type when it is free; any free one otherwise.
            for (port in FIRST_PORT until FIRST_PORT + 6) {
                runCatching { return ServerSocket(port, 8) }
            }
            return ServerSocket(0, 8)
        }

        /** "a=1&b=two+words" from a web form, as a map. */
        fun fields(body: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (pair in body.split('&')) {
                val at = pair.indexOf('=')
                if (at <= 0) continue
                val name = runCatching { URLDecoder.decode(pair.substring(0, at), "UTF-8") }.getOrNull() ?: continue
                out[name] = runCatching { URLDecoder.decode(pair.substring(at + 1), "UTF-8") }.getOrDefault("")
            }
            return out
        }

        /**
         * Works out the sign-in from what was sent: the three boxes when they are filled in,
         * otherwise whatever can be found in the pasted text. Also returns any spare server
         * addresses the message listed. Null when nothing usable was sent.
         */
        fun accountFrom(fields: Map<String, String>): Pair<TvAccount, List<String>>? {
            val server = fields["server"].orEmpty().trim()
            val username = fields["username"].orEmpty().trim()
            val password = fields["password"].orEmpty().trim()
            if (server.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
                return TvAccount(id = "", name = "", type = TvAccount.XTREAM, server = server, username = username, password = password) to emptyList()
            }
            val text = fields["text"].orEmpty().trim()
            if (text.isEmpty()) return null
            val login = Xtream.loginFromText(text)
            if (login != null) {
                val account = TvAccount(id = "", name = "", type = TvAccount.XTREAM, server = login.server, username = login.username, password = login.password)
                return account to Xtream.serversInText(text).filter { it != login.server }
            }
            // A single link and nothing else: a playlist.
            if (text.startsWith("http", ignoreCase = true) && text.none { it.isWhitespace() }) {
                return TvAccount(id = "", name = "", type = TvAccount.PLAYLIST, playlistUrl = text) to emptyList()
            }
            return null
        }

        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private const val PAGE_START = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sign in on your TV</title>
<style>
body{margin:0;padding:22px 18px 40px;background:#07070c;color:#f6f6fb;font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:520px;margin:0 auto}
h1{font-size:26px;margin:6px 0 10px}
p{color:#a9a9bd;margin:8px 0 14px}
label{display:block;margin:14px 0 6px;font-weight:600}
input,textarea{box-sizing:border-box;width:100%;padding:13px;border-radius:12px;border:1px solid #32324a;background:#1a1a25;color:#f6f6fb;font:inherit}
button{margin-top:22px;width:100%;padding:15px;border:0;border-radius:999px;background:linear-gradient(90deg,#8b5cf6,#ff4d9d);color:#fff;font:600 17px system-ui,sans-serif}
.bad{color:#ffb648;background:#2a2114;padding:12px;border-radius:12px}
.or{text-align:center;margin:18px 0 0}
.small{font-size:13px;margin-top:20px}
</style></head><body><main>"""
        private const val PAGE_END = "</main></body></html>"
    }
}
