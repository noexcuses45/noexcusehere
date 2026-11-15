package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.cast.Hls
import com.noexcusehere.mashwave.iptv.M3u
import com.noexcusehere.mashwave.iptv.TvAccount
import com.noexcusehere.mashwave.iptv.TvException
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvNet
import com.noexcusehere.mashwave.iptv.TvRef
import com.noexcusehere.mashwave.iptv.TvRegions
import com.noexcusehere.mashwave.iptv.TvSearch
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.iptv.Xtream
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class TvTest {
    private val account = TvAccount(id = "a1", name = "Test", server = "http://tv.example.com:8080", username = "me", password = "se cret")

    private fun obj(text: String): JsonObject = Xtream.json.parseToJsonElement(text) as JsonObject

    private fun list(text: String) = Xtream.elements(text.byteInputStream())

    // ---- signing in ------------------------------------------------------------------------

    @Test
    fun tidiesServerAddresses() {
        assertEquals("http://tv.example.com:8080", Xtream.normalizeServer(" tv.example.com:8080/ "))
        assertEquals("http://tv.example.com", Xtream.normalizeServer("http://tv.example.com/player_api.php?username=a&password=b"))
        assertEquals("https://tv.example.com", Xtream.normalizeServer("https://tv.example.com/get.php"))
        assertEquals("http://tv.example.com", Xtream.normalizeServer("http://tv.example.com/c/"))
        assertEquals("", Xtream.normalizeServer("   "))
    }

    @Test
    fun findsTheLoginInsideAPlaylistLink() {
        val login = Xtream.loginFromLink("http://tv.example.com:8080/get.php?username=me&password=se%20cret&type=m3u_plus&output=mpegts")
        assertNotNull(login)
        assertEquals("http://tv.example.com:8080", login!!.server)
        assertEquals("me", login.username)
        assertEquals("se cret", login.password)
        assertNull(Xtream.loginFromLink("http://tv.example.com/playlist.m3u"))
        assertNull(Xtream.loginFromLink("http://tv.example.com/get.php?username=me"))
    }

    @Test
    fun findsTheLoginInAPastedMessage() {
        val message = """
            tv.example.com
            http://tv.example.com

            👤 Username: Abc123xy
            🔑 Password: Zz99aaBB
            🔗 DNS (URL): http://tv.example.com
        """.trimIndent()
        val login = Xtream.loginFromText(message)
        assertNotNull(login)
        assertEquals("http://tv.example.com", login!!.server)
        assertEquals("Abc123xy", login.username)
        assertEquals("Zz99aaBB", login.password)

        val link = Xtream.loginFromText("here you go http://tv.example.com/get.php?username=u1&password=p1&type=m3u_plus&output=mpegts enjoy")
        assertEquals("u1", link!!.username)
        assertEquals("p1", link.password)
        assertNull(Xtream.loginFromText("Username: someone"))
    }

    @Test
    fun findsTheSpareAddressInAMessage() {
        val message = """
            tv.example.com
            http://tv.example.com
            Username: Abc123xy
            Password: Zz99aaBB
            DNS: http://tv.example.com
            Samsung and LG DNS: http://spare.example.net
        """.trimIndent()
        assertEquals(listOf("http://tv.example.com", "http://spare.example.net"), Xtream.serversInText(message))
        assertEquals("http://tv.example.com", Xtream.loginFromText(message)!!.server)
        assertEquals(listOf("http://tv.example.com:8080"), Xtream.serversInText("http://tv.example.com:8080/get.php?username=a&password=b&type=m3u_plus"))
        assertTrue(Xtream.serversInText("no links here").isEmpty())
    }

    @Test
    fun buildsAddresses() {
        assertEquals("http://tv.example.com:8080/player_api.php?username=me&password=se+cret", Xtream.api(account))
        assertEquals(
            "http://tv.example.com:8080/player_api.php?username=me&password=se+cret&action=get_series_info&series_id=7",
            Xtream.api(account, "get_series_info", "series_id" to "7"),
        )
        assertEquals("http://tv.example.com:8080/live/me/se%20cret/12.ts", Xtream.liveUrl(account, "12"))
        assertEquals("http://tv.example.com:8080/live/me/se%20cret/12.m3u8", Xtream.liveUrl(account, "12", "m3u8"))
        assertEquals("http://tv.example.com:8080/movie/me/se%20cret/5.mkv", Xtream.movieUrl(account, "5", "mkv"))
        assertEquals("http://tv.example.com:8080/series/me/se%20cret/9.mp4", Xtream.episodeUrl(account, "9", ""))
    }

    @Test
    fun readsTheSignInAnswer() {
        val good = Xtream.session(
            obj("""{"user_info":{"auth":1,"status":"Active","exp_date":"1767225600","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{}}"""),
        )
        assertTrue(good.ok)
        assertEquals(1767225600000L, good.expiresAt)
        assertEquals(2, good.maxConnections)
        assertEquals(listOf("m3u8", "ts"), good.formats)

        assertFalse(Xtream.session(obj("""{"user_info":{"auth":0}}""")).ok)
        val expired = Xtream.session(obj("""{"user_info":{"auth":"1","status":"Expired","exp_date":null}}"""))
        assertFalse(expired.ok)
        assertTrue(expired.message.contains("run out"))
        assertFalse(Xtream.session(obj("""{"hello":"world"}""")).ok)
    }

    @Test
    fun saysWhyAServerCouldNotBeReached() {
        val refused = java.net.ConnectException("Failed to connect to tv.example.com/203.0.113.9:80")
            .apply { initCause(java.net.ConnectException("failed to connect to tv.example.com/203.0.113.9 (port 80) from /192.168.1.20 (port 41234) after 15000ms: isConnected failed: ECONNREFUSED (Connection refused)")) }
        assertEquals(TvNet.Trouble.REFUSED, TvNet.classify(refused))
        assertEquals(TvNet.Trouble.UNREACHABLE, TvNet.classify(java.net.ConnectException("connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(TvNet.Trouble.TIMED_OUT, TvNet.classify(java.net.SocketTimeoutException("failed to connect after 15000ms")))
        assertEquals(TvNet.Trouble.TIMED_OUT, TvNet.classify(java.net.ConnectException("connect failed: ETIMEDOUT (Connection timed out)")))
        assertEquals(TvNet.Trouble.NO_NAME, TvNet.classify(java.net.UnknownHostException("tv.example.com")))
        assertEquals(TvNet.Trouble.OTHER, TvNet.classify(java.io.IOException("unexpected end of stream")))

        val plain = TvNet.describe(TvNet.Trouble.REFUSED, "tv.example.com", 80, listOf("203.0.113.9"))
        assertTrue(plain.startsWith("tv.example.com refused the connection"))
        assertTrue(plain.contains("Refused by 203.0.113.9, port 80"))
        assertTrue(plain.contains("other DNS"))

        // A filter on the network answers "0.0.0.0": that is a block, not a wrong address.
        val blocked = TvNet.describe(TvNet.Trouble.REFUSED, "tv.example.com", 80, listOf("0.0.0.0"))
        assertTrue(blocked.contains("dead end"))
        assertTrue(blocked.contains("Private DNS"))
        assertTrue(TvNet.isDeadEnd("127.0.0.1"))
        assertTrue(TvNet.isDeadEnd("::1"))
        assertFalse(TvNet.isDeadEnd("203.0.113.9"))

        assertTrue(TvNet.describe(TvNet.Trouble.TIMED_OUT, "tv.example.com", 8080, listOf("203.0.113.9")).contains("No answer from 203.0.113.9, port 8080"))
        assertTrue(TvNet.describe(TvNet.Trouble.NO_NAME, "tv.example.com", 80, emptyList()).startsWith("Can't find tv.example.com"))
        assertTrue(TvNet.describe(TvNet.Trouble.REFUSED, "cdn.example.net", 80, listOf("203.0.113.9"), forwarded = true).contains("where the service forwarded the app"))
        // Forwarded to nowhere: the service is turning the app away, which is not the network's doing.
        val turnedAway = TvNet.describe(TvNet.Trouble.REFUSED, "127.0.0.1", 80, listOf("127.0.0.1"), forwarded = true)
        assertTrue(turnedAway.startsWith("The service turned the app away"))
        assertFalse(turnedAway.contains("Wi-Fi"))
        assertEquals(listOf("mine", "UltimateBeats/1.0 (Linux;Android) AndroidXMedia3/1.9.4", TvNet.LIBRARY_AGENT), TvNet.agents(" mine ").filter { it != TvNet.platformAgent })
    }

    @Test
    fun aRealRefusalIsReportedWithTheAddressItCameFrom() {
        // Nothing listens on this port of this machine, so the connection is refused for real.
        val free = java.net.ServerSocket(0).use { it.localPort }
        try {
            TvNet.open("http://127.0.0.1:$free/player_api.php?username=u&password=p", connectMs = 3000)
            fail("expected a refusal")
        } catch (e: TvException) {
            assertTrue(e.network)
            assertTrue(e.message!!, e.message!!.contains("127.0.0.1"))
            assertFalse("the login must not be repeated in the message", e.message!!.contains("password") || e.message!!.contains("username"))
        }
    }

    // ---- lists -----------------------------------------------------------------------------

    @Test
    fun readsChannelsHoweverTheNumbersAreWritten() {
        val items = Xtream.items(
            list(
                """[
                {"num":1,"name":"ABC News","stream_type":"live","stream_id":101,"stream_icon":"http://x/abc.png","epg_channel_id":"abc.au","category_id":"3","added":"1700000000"},
                {"num":"2","name":" Nine ","stream_id":"102","stream_icon":null,"category_id":4,"epg_channel_id":null},
                {"name":"No id"},
                {"num":4,"name":"Seven","stream_id":104,"category_id":null,"category_ids":[9,3]}
                ]""",
            ),
            TvSection.LIVE,
        )
        assertEquals(3, items.size)
        assertEquals("101", items[0].id)
        assertEquals("abc.au", items[0].epgId)
        assertEquals(1700000000L, items[0].added)
        assertEquals("Nine", items[1].name)
        assertEquals("", items[1].logo)
        assertEquals("4", items[1].category)
        assertEquals(2, items[1].number)
        assertEquals("9", items[2].category)
    }

    @Test
    fun readsListsSentAsNumberedObjects() {
        val categories = Xtream.categories(list("""{"1":{"category_id":"10","category_name":"UK | Sports","parent_id":0},"2":{"category_id":"11","category_name":"AU | News"}}"""))
        assertEquals(listOf("UK | Sports", "AU | News"), categories.map { it.name })
        assertTrue(Xtream.categories(list("[]")).isEmpty())
    }

    @Test
    fun readsMoviesAndShows() {
        val movies = Xtream.items(
            list("""[{"num":1,"name":"Big Film (2020)","stream_type":"movie","stream_id":55,"stream_icon":"http://x/p.jpg","rating":"7.4","rating_5based":3.7,"added":"1690000000","category_id":"8","container_extension":"mkv"}]"""),
            TvSection.MOVIES,
        )
        assertEquals("mkv", movies[0].ext)
        assertEquals(7.4f, movies[0].rating, 0.001f)

        val shows = Xtream.items(
            list("""[{"num":1,"name":"Some Show","series_id":77,"cover":"http://x/c.jpg","rating":8,"last_modified":"1695000000","category_id":"2"}]"""),
            TvSection.SHOWS,
        )
        assertEquals("77", shows[0].id)
        assertEquals("http://x/c.jpg", shows[0].logo)
        assertTrue(shows[0].opensDetails)
    }

    @Test
    fun aListAnsweredWithTheSignInPageMeansTheLoginStoppedWorking() {
        try {
            Xtream.items(list("""{"user_info":{"auth":0}}"""), TvSection.LIVE)
            fail("expected a refusal")
        } catch (e: TvException) {
            assertTrue(e.message!!.contains("not accepted"))
        }
    }

    @Test
    fun readsAShowsEpisodesInEitherShape() {
        val bySeason = Xtream.series(
            obj(
                """{"seasons":[],"info":{"name":"Some Show","plot":"A story.","cast":"A, B","genre":"Drama","releaseDate":"2019-01-01","rating":"8.1","cover":"http://x/c.jpg","backdrop_path":["http://x/b.jpg"],"episode_run_time":"45"},
                "episodes":{"2":[{"id":"2001","episode_num":1,"title":"Back","container_extension":"mp4","info":[],"season":2}],
                            "1":[{"id":"1002","episode_num":"2","title":"Second","container_extension":"mkv","info":{"duration_secs":2700,"plot":"More.","movie_image":"http://x/e2.jpg"},"season":1},
                                 {"id":"1001","episode_num":1,"title":"Pilot","container_extension":"mkv","info":{"duration":"00:44:10"},"season":1}]}}""",
            ),
        )
        assertEquals(listOf("1001", "1002", "2001"), bySeason.episodes.map { it.id })
        assertEquals(listOf(1, 2), bySeason.seasons)
        assertEquals(2650, bySeason.episodes[0].durationSec)
        assertEquals(2700, bySeason.episodes[1].durationSec)
        assertEquals("http://x/b.jpg", bySeason.backdrop)
        assertEquals(2700, bySeason.durationSec)
        assertEquals("Drama", bySeason.genre)

        val asLists = Xtream.series(obj("""{"info":[],"episodes":[[{"id":11,"episode_num":1,"title":"One","season":1}],[{"id":21,"episode_num":1,"title":"Two-One","season":2}]]}"""))
        assertEquals(listOf("11", "21"), asLists.episodes.map { it.id })
        assertEquals("mp4", asLists.episodes[0].ext)
        assertEquals("", asLists.plot)
    }

    @Test
    fun readsAMoviePageEvenWhenDetailsAreMissing() {
        val full = Xtream.movie(obj("""{"info":{"movie_image":"http://x/m.jpg","plot":"Boom.","duration_secs":"7200","genre":"Action","backdrop_path":"http://x/bd.jpg"},"movie_data":{"stream_id":55,"name":"Big Film","container_extension":"mp4"}}"""))
        assertEquals("Big Film", full.name)
        assertEquals(7200, full.durationSec)
        assertEquals("mp4", full.ext)
        assertEquals("http://x/bd.jpg", full.backdrop)
        val empty = Xtream.movie(obj("""{"info":[],"movie_data":{"stream_id":55,"name":"Big Film"}}"""))
        assertEquals("", empty.plot)
        assertEquals(0, empty.durationSec)
    }

    @Test
    fun readsTheGuide() {
        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        val guide = Xtream.guide(
            obj(
                """{"epg_listings":[
                {"title":"${b64("Late News")}","description":"${b64("Headlines.")}","start_timestamp":"1700003600","stop_timestamp":"1700007200"},
                {"title":"${b64("Evening News")}","description":"","start_timestamp":1700000000,"stop_timestamp":1700003600},
                {"title":"","start_timestamp":"1","stop_timestamp":"2"}]}""",
            ),
        )
        assertEquals(listOf("Evening News", "Late News"), guide.map { it.title })
        assertEquals("Headlines.", guide[1].description)
        assertEquals(0.5f, guide[0].progress(1700001800000L), 0.001f)
        assertTrue(Xtream.guide(obj("""{"epg_listings":[]}""")).isEmpty())
    }

    // ---- playlists -------------------------------------------------------------------------

    @Test
    fun readsAPlaylist() {
        val text = "﻿#EXTM3U url-tvg=\"http://x/guide.xml\"\n" +
            "#EXTINF:-1 tvg-id=\"abc.au\" tvg-name=\"ABC\" tvg-logo=\"http://x/abc.png\" tvg-country=\"AU\" group-title=\"News, Australia\",ABC News, 24/7\n" +
            "http://tv.example.com/live/u/p/1.ts\n" +
            "\n" +
            "#EXTINF:-1,Plain Channel\n" +
            "#EXTGRP:Sport\n" +
            "#EXTVLCOPT:http-user-agent=Something\n" +
            "http://tv.example.com/live/u/p/2.m3u8\n" +
            "#EXTINF:-1 group-title=\"Films\",Big Film (2020)\n" +
            "http://tv.example.com/movie/u/p/3.mkv\n" +
            "#EXTINF:-1 group-title=\"Shows\",Some Show S01 E02\n" +
            "http://tv.example.com/series/u/p/4.mp4\n" +
            "#EXTINF:-1 group-title=\"Films\",Big Film (2020)\n" +
            "http://tv.example.com/movie/u/p/3.mkv\n" +
            "http://tv.example.com/stream/bare\n"
        val entries = ArrayList<M3u.Entry>()
        M3u.parse(text.reader().buffered()) { entries += it }
        assertEquals(6, entries.size)
        assertEquals("ABC News, 24/7", entries[0].name)
        assertEquals("News, Australia", entries[0].group)
        assertEquals("http://x/abc.png", entries[0].logo)
        assertEquals("AU", entries[0].country)
        assertEquals("Sport", entries[1].group)
        assertEquals("bare", entries[5].name)
        assertEquals("Other", entries[5].group)

        val catalogs = M3u.catalogs(text.reader().buffered(), 1)
        assertEquals(3, catalogs.getValue(TvSection.LIVE).items.size)
        assertEquals(1, catalogs.getValue(TvSection.MOVIES).items.size)
        assertEquals(1, catalogs.getValue(TvSection.SHOWS).items.size)
        assertEquals(listOf("News, Australia", "Sport", "Other"), catalogs.getValue(TvSection.LIVE).categories.map { it.name })
        val film = catalogs.getValue(TvSection.MOVIES).items[0]
        assertFalse(film.opensDetails)
        assertEquals(film.url, film.id)
    }

    @Test
    fun tellsChannelsFromMoviesByTheAddress() {
        assertEquals(TvSection.LIVE, M3u.sectionOf("http://h/live/u/p/1.ts", "ABC"))
        assertEquals(TvSection.LIVE, M3u.sectionOf("http://h/u/p/1", "ABC"))
        assertEquals(TvSection.LIVE, M3u.sectionOf("http://h/hls/abc/index.m3u8?token=1.mp4", "ABC"))
        assertEquals(TvSection.MOVIES, M3u.sectionOf("http://h/movie/u/p/1.mkv", "Film"))
        assertEquals(TvSection.MOVIES, M3u.sectionOf("http://h/files/film.MP4", "Film"))
        assertEquals(TvSection.SHOWS, M3u.sectionOf("http://h/series/u/p/1.mkv", "Show"))
        assertEquals(TvSection.SHOWS, M3u.sectionOf("http://h/files/x.mp4", "Show s02e11"))
    }

    @Test
    fun aWebPageIsNotAPlaylist() {
        val page = (1..80).joinToString("\n") { "<p>line $it of an error page</p>" }
        try {
            M3u.parse(page.reader().buffered()) { }
            fail("expected a refusal")
        } catch (e: TvException) {
            assertTrue(e.message!!.contains("playlist"))
        }
    }

    // ---- countries -------------------------------------------------------------------------

    @Test
    fun findsTheCountryInCategoryNames() {
        fun key(name: String) = TvRegions.of(name)?.key
        assertEquals("United Kingdom", key("UK | Sports"))
        assertEquals("United Kingdom", key("|UK| ENTERTAINMENT"))
        assertEquals("United Kingdom", key("UK Sports"))
        assertEquals("United States", key("[US] News"))
        assertEquals("United States", key("USA: Movies"))
        assertEquals("United States", key("US NEWS"))
        assertEquals("Australia", key("AU - Entertainment"))
        assertEquals("Australia", key("★ AUSTRALIA ★"))
        assertEquals("Australia", key("VIP | AU | SPORTS"))
        assertEquals("Australia", key("4K AU ► Sports"))
        assertEquals("France", key("🇫🇷 Cinéma"))
        assertEquals("Germany", key("Sports (DE)"))
        assertEquals("Germany", key("News | DE"))
        assertEquals("India", key("IN | Hindi"))
        assertEquals("India", key("HINDI MOVIES"))
        assertEquals("Italy", key("Italian Movies"))
        assertEquals("Arabic", key("AR | beIN"))
        assertEquals("Argentina", key("ARG | Deportes"))
        assertEquals("Balkans", key("EX-YU | Sport"))
        assertEquals("New Zealand", key("NEW ZEALAND"))
        assertEquals("United Kingdom", key("United Kingdom - Kids"))
        assertEquals("English", key("EN | Drama"))
        assertEquals("English", key("English Movies"))
    }

    @Test
    fun doesNotInventCountries() {
        fun key(name: String) = TvRegions.of(name)?.key
        assertNull(key("In Cinemas"))
        assertNull(key("IN CINEMAS NOW"))
        assertNull(key("Movies in 4K"))
        assertNull(key("Kids"))
        assertNull(key("NBA | League Pass"))
        assertNull(key("Sports"))
        assertNull(key("24/7 Shows"))
        assertNull(key("Action & Adventure"))
        assertNull(key("It Chapter Collection"))
        assertNull(key("Al Jazeera"))
        assertNull(key(""))
    }

    @Test
    fun countryButtonsPutHomeFirstAndSkipEmptyOnes() {
        val categories = listOf("UK | Sports", "AU | News", "FR | Cinema", "UK | Kids", "Other things", "DE | Empty")
            .mapIndexed { i, name -> com.noexcusehere.mashwave.iptv.TvCategory(i.toString(), name) }
        val counts = mapOf("0" to 5, "1" to 2, "2" to 1, "3" to 4, "4" to 9)
        val buttons = TvRegions.buttons(categories, counts, "au")
        assertEquals(listOf("Australia", "France", "United Kingdom"), buttons.map { it.key })
        assertTrue(buttons[0].label.endsWith("Australia"))
        assertEquals("AU", TvRegions.ofCode("au")?.label?.takeLast(9)?.let { "AU" })
        assertEquals("Australia", TvRegions.ofCode("AU")?.key)
    }

    // ---- search ----------------------------------------------------------------------------

    @Test
    fun searchWantsEveryWordAndRanksStartsFirst() {
        val names = listOf("UK: Sky Sports Main Event", "Sportsnet One", "US: Fox Sports 1", "Sky News", "UK: BBC One HD", "One Day (2011)", "BBC Earth")
        val items = names.mapIndexed { i, n -> TvItem(TvSection.LIVE, i.toString(), n) }
        assertEquals(listOf("UK: Sky Sports Main Event"), TvSearch.find(items, "sky sport").map { it.name })
        assertEquals(listOf("UK: BBC One HD", "BBC Earth"), TvSearch.find(items, "bbc").map { it.name })
        assertEquals("One Day (2011)", TvSearch.find(items, "one").first().name)
        assertEquals(3, TvSearch.find(items, " SPORTS ").size)
        assertTrue(TvSearch.find(items, "   ").isEmpty())
        assertTrue(TvSearch.find(items, "zzz").isEmpty())
        assertEquals(2, TvSearch.find(items, "s", limit = 2).size)
    }

    // ---- passing a stream through the phone ------------------------------------------------

    @Test
    fun rewritesEveryAddressInAStreamList() {
        val playlist = "#EXTM3U\n" +
            "#EXT-X-VERSION:3\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n" +
            "#EXTINF:6.0,\n" +
            "seg-1.ts\n" +
            "#EXTINF:6.0,\n" +
            "/abs/seg-2.ts?t=1\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000\n" +
            "http://other.example.com/low/index.m3u8\n"
        val out = Hls.rewrite(playlist, "http://cdn.example.com/live/42/index.m3u8?token=abc") { "P[$it]" }
        val lines = out.lines()
        assertEquals("#EXTM3U", lines[0])
        assertEquals("#EXT-X-KEY:METHOD=AES-128,URI=\"P[http://cdn.example.com/live/42/key.bin]\",IV=0x1", lines[2])
        assertEquals("P[http://cdn.example.com/live/42/seg-1.ts]", lines[4])
        assertEquals("P[http://cdn.example.com/abs/seg-2.ts?t=1]", lines[6])
        assertEquals("P[http://other.example.com/low/index.m3u8]", lines[8])

        assertTrue(Hls.isPlaylist("application/vnd.apple.mpegurl", "http://h/x", ""))
        assertTrue(Hls.isPlaylist(null, "http://h/live/1.m3u8?x=1", ""))
        assertTrue(Hls.isPlaylist("text/plain", "http://h/x", "#EXTM3U\n#EXT-X"))
        assertFalse(Hls.isPlaylist("video/mp2t", "http://h/live/1.ts", "G@\u0000"))
    }

    @Test
    fun streamNotesSurviveARoundTrip() {
        val ref = TvRef(TvRef.EPISODE, "acct", "9001", "77")
        assertEquals(ref, TvRef.parse(ref.encode()))
        assertTrue(TvRef.parse(TvRef(TvRef.LIVE, "a", "1").encode())!!.isLive)
        assertNull(TvRef.parse(""))
        assertNull(TvRef.parse("garbage"))
    }
}
