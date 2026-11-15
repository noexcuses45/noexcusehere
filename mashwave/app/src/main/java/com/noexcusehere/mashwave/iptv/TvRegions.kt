package com.noexcusehere.mashwave.iptv

import java.util.Locale

/**
 * Works out which country a category belongs to from the way services name them:
 * "UK | Sports", "[US] News", "AU: Entertainment", "🇫🇷 France", "Sports (DE)" ...
 * That is what lets the TV tab offer one button per country.
 */
object TvRegions {
    /** [key] groups categories together; [label] is what the button says. */
    data class Region(val key: String, val label: String)

    private class Known(val name: String, val iso: String, vararg val words: String)

    private val known = listOf(
        Known("Australia", "AU", "AU", "AUS", "AUSTRALIA", "AUSTRALIAN"),
        Known("New Zealand", "NZ", "NZ", "NZL", "NEW ZEALAND"),
        Known("United Kingdom", "GB", "UK", "GB", "GBR", "ENGLAND", "BRITISH", "UNITED KINGDOM"),
        Known("United States", "US", "US", "USA", "UNITED STATES", "AMERICA", "AMERICAN"),
        Known("Canada", "CA", "CA", "CAN", "CANADA"),
        Known("Ireland", "IE", "IE", "IRL", "IRELAND", "IRISH"),
        Known("India", "IN", "IN", "IND", "INDIA", "INDIAN", "HINDI", "TAMIL", "TELUGU", "MALAYALAM", "KANNADA", "MARATHI", "GUJARATI", "PUNJABI"),
        Known("Pakistan", "PK", "PK", "PAK", "PAKISTAN", "URDU"),
        Known("Bangladesh", "BD", "BD", "BANGLADESH", "BANGLA", "BENGALI"),
        Known("Sri Lanka", "LK", "LK", "SRI LANKA", "SINHALA"),
        Known("Nepal", "NP", "NP", "NEPAL"),
        Known("France", "FR", "FR", "FRA", "FRANCE", "FRENCH"),
        Known("Germany", "DE", "DE", "GER", "DEU", "GERMANY", "GERMAN", "DEUTSCHLAND"),
        Known("Italy", "IT", "IT", "ITA", "ITALY", "ITALIA", "ITALIAN"),
        Known("Spain", "ES", "ES", "ESP", "SPAIN", "ESPANA", "SPANISH"),
        Known("Portugal", "PT", "PT", "POR", "PRT", "PORTUGAL", "PORTUGUESE"),
        Known("Netherlands", "NL", "NL", "NED", "NLD", "NETHERLANDS", "HOLLAND", "DUTCH"),
        Known("Belgium", "BE", "BE", "BEL", "BELGIUM"),
        Known("Switzerland", "CH", "CH", "SUI", "SWITZERLAND", "SWISS"),
        Known("Austria", "AT", "AT", "AUT", "AUSTRIA"),
        Known("Sweden", "SE", "SE", "SWE", "SWEDEN"),
        Known("Norway", "NO", "NO", "NOR", "NORWAY"),
        Known("Denmark", "DK", "DK", "DEN", "DNK", "DENMARK"),
        Known("Finland", "FI", "FI", "FIN", "FINLAND"),
        Known("Poland", "PL", "PL", "POL", "POLAND", "POLSKA"),
        Known("Romania", "RO", "RO", "ROM", "ROU", "ROMANIA"),
        Known("Greece", "GR", "GR", "GRE", "GRC", "GREECE", "GREEK"),
        Known("Turkey", "TR", "TR", "TUR", "TURKEY", "TURKIYE", "TURKISH"),
        Known("Russia", "RU", "RU", "RUS", "RUSSIA", "RUSSIAN"),
        Known("Ukraine", "UA", "UA", "UKR", "UKRAINE"),
        Known("Albania", "AL", "AL", "ALB", "ALBANIA", "ALBANIAN", "SHQIP"),
        Known("Bulgaria", "BG", "BG", "BUL", "BGR", "BULGARIA"),
        Known("Hungary", "HU", "HU", "HUN", "HUNGARY"),
        Known("Czechia", "CZ", "CZ", "CZE", "CZECH"),
        Known("Slovakia", "SK", "SK", "SVK", "SLOVAKIA"),
        Known("Slovenia", "SI", "SI", "SVN", "SLOVENIA"),
        Known("Croatia", "HR", "HR", "CRO", "HRV", "CROATIA"),
        Known("Serbia", "RS", "RS", "SRB", "SERBIA"),
        Known("Bosnia", "BA", "BA", "BIH", "BOSNIA"),
        Known("North Macedonia", "MK", "MK", "MKD", "MACEDONIA"),
        Known("Montenegro", "ME", "MNE", "MONTENEGRO"),
        Known("Balkans", "", "EXYU", "EX-YU", "EX YU", "YU", "BALKAN", "BALKANS"),
        Known("Arabic", "", "AR", "ARA", "ARAB", "ARABIC", "ARABE"),
        Known("Egypt", "EG", "EG", "EGY", "EGYPT"),
        Known("Saudi Arabia", "SA", "SA", "KSA", "SAUDI"),
        Known("UAE", "AE", "AE", "UAE", "EMIRATES"),
        Known("Morocco", "MA", "MA", "MAR", "MOROCCO", "MAROC"),
        Known("Algeria", "DZ", "DZ", "ALG", "DZA", "ALGERIA"),
        Known("Tunisia", "TN", "TN", "TUN", "TUNISIA"),
        Known("Iraq", "IQ", "IQ", "IRQ", "IRAQ"),
        Known("Syria", "SY", "SY", "SYR", "SYRIA"),
        Known("Lebanon", "LB", "LB", "LBN", "LEBANON"),
        Known("Jordan", "JO", "JO", "JOR", "JORDAN"),
        Known("Kuwait", "KW", "KW", "KUWAIT"),
        Known("Qatar", "QA", "QA", "QATAR"),
        Known("Iran", "IR", "IR", "IRN", "IRAN", "PERSIAN", "FARSI"),
        Known("Afghanistan", "AF", "AF", "AFG", "AFGHANISTAN", "AFGHAN"),
        Known("Kurdish", "", "KU", "KURD", "KURDISH", "KURDISTAN"),
        Known("Israel", "IL", "IL", "ISR", "ISRAEL", "HEBREW"),
        Known("Armenia", "AM", "AM", "ARM", "ARMENIA"),
        Known("Azerbaijan", "AZ", "AZ", "AZE", "AZERBAIJAN"),
        Known("Georgia", "GE", "GE", "GEO", "GEORGIA"),
        Known("Africa", "", "AFR", "AFRICA", "AFRICAN"),
        Known("South Africa", "ZA", "ZA", "RSA", "SOUTH AFRICA"),
        Known("Nigeria", "NG", "NG", "NGA", "NIGERIA"),
        Known("Ghana", "GH", "GH", "GHA", "GHANA"),
        Known("Kenya", "KE", "KE", "KEN", "KENYA"),
        Known("Ethiopia", "ET", "ET", "ETH", "ETHIOPIA"),
        Known("Somalia", "SO", "SO", "SOM", "SOMALIA", "SOMALI"),
        Known("Brazil", "BR", "BR", "BRA", "BRAZIL", "BRASIL"),
        Known("Mexico", "MX", "MX", "MEX", "MEXICO"),
        Known("Argentina", "AR", "ARG", "ARGENTINA"),
        Known("Colombia", "CO", "CO", "COL", "COLOMBIA"),
        Known("Chile", "CL", "CL", "CHL", "CHILE"),
        Known("Peru", "PE", "PE", "PER", "PERU"),
        Known("Venezuela", "VE", "VE", "VEN", "VENEZUELA"),
        Known("Latin America", "", "LAT", "LATAM", "LATIN", "LATINO", "LATIN AMERICA"),
        Known("Caribbean", "", "CARIBBEAN", "CARIB"),
        Known("Jamaica", "JM", "JM", "JAM", "JAMAICA"),
        Known("China", "CN", "CN", "CHN", "CHINA", "CHINESE"),
        Known("Hong Kong", "HK", "HK", "HKG", "HONG KONG"),
        Known("Taiwan", "TW", "TW", "TWN", "TAIWAN"),
        Known("Japan", "JP", "JP", "JPN", "JAPAN", "JAPANESE"),
        Known("Korea", "KR", "KR", "KOR", "KOREA", "KOREAN"),
        Known("Philippines", "PH", "PH", "PHL", "PHILIPPINES", "FILIPINO", "PINOY"),
        Known("Thailand", "TH", "TH", "THA", "THAILAND", "THAI"),
        Known("Vietnam", "VN", "VN", "VIE", "VNM", "VIETNAM"),
        Known("Indonesia", "ID", "ID", "IDN", "INDONESIA"),
        Known("Malaysia", "MY", "MY", "MYS", "MALAYSIA"),
        Known("Singapore", "SG", "SG", "SGP", "SINGAPORE"),
        Known("Nordic", "", "NORDIC", "NORDICS", "SCANDINAVIA", "SCANDINAVIAN"),
        Known("Asia", "", "ASIA", "ASIAN"),
        Known("Europe", "", "EU", "EUROPE", "EUROPEAN"),
        Known("English", "", "EN", "ENG", "ENGLISH"),
    )

    private val byWord: Map<String, Known> = HashMap<String, Known>().apply { known.forEach { k -> k.words.forEach { putIfAbsent(it, k) } } }
    private val byIso: Map<String, Known> = known.filter { it.iso.isNotEmpty() }.associateBy { it.iso }
    private val phrases: List<Pair<String, Known>> = byWord.entries.filter { ' ' in it.key }.map { it.key to it.value }

    /** Two-letter codes that are also everyday words: only believed with a clear divider after them. */
    private val shy = setOf("IN", "IT", "NO", "AT", "BE", "ME", "SO", "AM", "MY", "ID", "SI", "CH", "AL", "ET", "GE", "MA", "SA")

    /** Labels that describe picture quality or kind, not a place; skipped when looking for the country. */
    private val tags = setOf(
        "4K", "8K", "UHD", "FHD", "HD", "SD", "HEVC", "H265", "H264", "VIP", "PPV", "VOD", "NEW", "TOP", "LIVE", "RAW", "HQ", "LQ",
        "BACKUP", "MULTI", "PREMIUM", "PLUS", "ALL", "TV", "IPTV", "THE",
    )

    private const val DIVIDERS = "|:-–—►▶»>]})/\\•·★✦➤✪●■◆~_="

    private fun flag(iso: String): String {
        if (iso.length != 2) return ""
        val base = 0x1F1E6
        return String(Character.toChars(base + (iso[0] - 'A'))) + String(Character.toChars(base + (iso[1] - 'A')))
    }

    private fun region(k: Known): Region = Region(k.name, if (k.iso.isEmpty()) k.name else flag(k.iso) + " " + k.name)

    /** The label for a bare country code, as playlists give it ("AU"). */
    fun ofCode(code: String): Region? {
        val c = code.trim().uppercase(Locale.ROOT)
        if (c.isEmpty()) return null
        val k = byIso[c] ?: byWord[c] ?: return if (c.length in 2..3 && c.all { it in 'A'..'Z' }) Region(c, c) else null
        return region(k)
    }

    /** The country (or language group) a category name points at, or null when it names none. */
    fun of(categoryName: String): Region? {
        val name = categoryName.trim()
        if (name.isEmpty()) return null
        flagAtStart(name)?.let { return it }

        val tokens = tokenize(name)
        if (tokens.isEmpty()) return null
        val upper = name.uppercase(Locale.ROOT)

        // A country written out in two words at the very start ("UNITED KINGDOM | Sports").
        val start = upper.trimStart { !it.isLetterOrDigit() }
        for ((phrase, k) in phrases) {
            if (start.startsWith(phrase) && (start.length == phrase.length || !start[phrase.length].isLetter())) return region(k)
        }

        // The first word that is not a quality tag ("VIP | UK | Sports" is about the UK).
        var index = 0
        while (index < tokens.size && tokens[index].text.uppercase(Locale.ROOT) in tags) index++
        if (index < tokens.size) {
            val token = tokens[index]
            val word = token.text.uppercase(Locale.ROOT)
            val k = byWord[word]
            if (k != null && believable(token, word)) return region(k)
        }

        // The last word, when it is set apart: "Sports | UK", "News (DE)".
        val last = tokens.last()
        if (tokens.size > 1 && last.setApart) {
            val word = last.text.uppercase(Locale.ROOT)
            val k = byWord[word]
            if (k != null && (word.length > 2 || last.text == word)) return region(k)
        }
        return null
    }

    private class Token(val text: String, val divided: Boolean, val setApart: Boolean)

    /** Splits into words, noting whether a divider follows (divided) or precedes / brackets it (setApart). */
    private fun tokenize(name: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < name.length) {
            if (!name[i].isLetterOrDigit()) {
                i++
                continue
            }
            val from = i
            while (i < name.length && (name[i].isLetterOrDigit() || (name[i] == '-' && i + 1 < name.length && name[i + 1].isLetter() && i > from && name.substring(from, i).equals("EX", true)))) i++
            val text = name.substring(from, i)
            var after = i
            while (after < name.length && name[after] == ' ') after++
            val divided = after >= name.length || name[after] in DIVIDERS
            var before = from - 1
            while (before >= 0 && name[before] == ' ') before--
            val setApart = before >= 0 && (name[before] in DIVIDERS || name[before] in "([{")
            out += Token(text, divided, setApart)
        }
        return out
    }

    private fun believable(token: Token, word: String): Boolean {
        // Codes must be written as codes ("UK", not "uk" inside a sentence).
        if (word.length <= 3 && token.text != word) return false
        if (word in shy) return token.divided
        return true
    }

    /** "🇬🇧 Sports" - the flag itself says which country. */
    private fun flagAtStart(name: String): Region? {
        var i = 0
        while (i < name.length && !name[i].isLetterOrDigit()) {
            val cp = name.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                val next = i + Character.charCount(cp)
                if (next < name.length) {
                    val cp2 = name.codePointAt(next)
                    if (cp2 in 0x1F1E6..0x1F1FF) {
                        val iso = "" + ('A' + (cp - 0x1F1E6)) + ('A' + (cp2 - 0x1F1E6))
                        return byIso[iso]?.let { region(it) } ?: Region(iso, flag(iso) + " " + iso)
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return null
    }

    /**
     * The country buttons for a section: every region that has channels, the phone's own country
     * first and the rest in alphabetical order.
     */
    fun buttons(categories: List<TvCategory>, counts: Map<String, Int>, homeIso: String): List<Region> {
        val seen = LinkedHashMap<String, Region>()
        for (category in categories) {
            if ((counts[category.id] ?: 0) == 0) continue
            val region = of(category.name) ?: continue
            seen.putIfAbsent(region.key, region)
        }
        val home = byIso[homeIso.uppercase(Locale.ROOT)]?.name
        return seen.values.sortedWith(compareBy({ it.key != home }, { it.key.lowercase(Locale.ROOT) }))
    }
}
