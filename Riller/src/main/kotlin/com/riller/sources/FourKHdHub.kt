package com.riller.sources

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.riller.core.RillerSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

class FourKHdHub : RillerSource("4KHDHub", "https://4khdhub.one/", TvType.Movie, TvType.TvSeries) {

    private val headers = mapOf("User-Agent" to BROWSER_UA)

    // GET ?s=<query> → a.movie-card cards. The site's id is the URL path; CS3
    // hands load() the SearchResponse url, so store the resolved absolute url
    // here and extract the path again in load().
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl, headers = headers, params = mapOf("s" to query)).document
        val base = URI(mainUrl.trimEnd('/'))
        return doc.select("a.movie-card").mapNotNull { card ->
            val href = card.attr("href")
            val uri = runCatching { base.resolve(href) }.getOrNull() ?: return@mapNotNull null
            if (uri.host != base.host) return@mapNotNull null
            val title = card.selectFirst(".movie-card-title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val meta = card.selectFirst(".movie-card-meta")?.text().orEmpty()
            val poster = card.selectFirst("img")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { base.resolve(it).toString() }.getOrNull() }
            val url = uri.toString()
            if (href.contains("-series-")) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    year = firstFourDigitYear(meta)
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    year = firstFourDigitYear(meta)
                }
            }
        }
    }

    // Detail page: everything nullable — the site's markup is hand-rolled and drifts.
    // Only a missing title is fatal (same as the Rust parser).
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val path = runCatching { URI(url).path }.getOrNull() ?: url
        val isSeries = path.contains("-series-")

        val rawTitle = doc.select("h1")
            .firstNotNullOfOrNull { it.text().trim().takeIf(String::isNotEmpty) }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("4KHDHub: title missing")
        val title = stripTrailingYear(rawTitle)

        val description = doc.select(".content-section p.mt-4")
            .firstNotNullOfOrNull { it.text().trim().takeIf(String::isNotEmpty) }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val imdb = doc.select(".imdb-score")
            .firstNotNullOfOrNull { it.text().trim().takeIf(String::isNotEmpty) }
            ?.let { Score.from(NUM_REGEX.find(it)?.value, 10) }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)
        val year = doc.metadata("Release:")?.let(::firstFourDigitYear)
            ?: doc.metadata("Last Air:")?.let(::firstFourDigitYear)
            ?: firstFourDigitYear(rawTitle)
        val genres = doc.select(".badge-outline a")
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .filter { it.lowercase() in GENRES }
        val stars = doc.metadata("Stars:")
            ?.split(",")?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }

        if (isSeries) {
            // Episodes live in download filenames, not a table: one Episode per
            // distinct (season, episode) found in SxxExx filenames.
            val episodes = doc.select("#episodes .episode-download-item")
                .mapNotNull { item ->
                    val m = SE_EP_REGEX.find(item.selectFirst(".episode-file-title")?.text().orEmpty())
                        ?: return@mapNotNull null
                    val season = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val episode = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    // fix = false: data is "path|s|e", not a url — the String
                    // overload would run fixUrl over it and corrupt the format.
                    // initializer must be a named arg: it isn't the overload's
                    // last parameter, so a trailing lambda can't bind to it.
                    newEpisode(
                        url = "$path|$season|$episode",
                        fix = false,
                        initializer = {
                            this.season = season
                            this.episode = episode
                        },
                    )
                }
                .distinctBy { "${it.season}:${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                score = imdb
                tags = genres.ifEmpty { null }
                addActors(stars)
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, path) {
            posterUrl = poster
            plot = description
            this.year = year
            score = imdb
            tags = genres.ifEmpty { null }
            addActors(stars)
        }
    }

    // data: "<path>" (movie) or "<path>|<s>|<e>" (series episode, fix = false above).
    // Port of client.rs resolve_release, minus the first-playable-wins loop: CS3
    // wants every link we can resolve, so rejected candidates just fall through.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("|")
        val path = parts.first().takeIf { it.startsWith("/") }
            ?: throw ErrorLoadingException("4KHDHub: bad link data")
        val season = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val episode = parts.getOrNull(2)?.toIntOrNull() ?: 0

        val page = app.get(mainUrl.trimEnd('/') + path, headers = headers)
        if (!page.isSuccessful) throw ErrorLoadingException("4KHDHub: page ${page.code}")
        val referer = mainUrl.trimEnd('/')

        val seen = mutableSetOf<String>()
        var emitted = 0
        for (release in parseReleases(page.document, season, episode)) {
            for (mirror in release.mirrors) {
                // One dead mirror must not kill the rest (Rust logs and moves on).
                val candidates = runCatching {
                    when {
                        "hubcloud." in mirror.url -> resolveHubcloud(mirror.url)
                        "hubdrive." in mirror.url -> resolveHubdrive(mirror.url)
                        // greenmotors-family ad wrapper hides the real resolver url
                        else -> resolveWrapped(mirror.url)
                    }
                }.getOrNull() ?: emptyList()
                for (candidate in candidates) {
                    val url = preflight(candidate.url, referer) ?: continue
                    if (!seen.add(url)) continue
                    callback(newExtractorLink(
                        source = name,
                        name = release.filename, // the site's own download title
                        url = url,
                        type = ExtractorLinkType.VIDEO, // preflight already gated content-type
                    ) {
                        this.referer = referer
                        this.quality = release.quality
                        this.headers = mapOf("User-Agent" to BROWSER_UA)
                    })
                    emitted++
                }
            }
        }
        return emitted > 0
    }

    // Port of parser.rs parse_releases: one Release per distinct normalized
    // filename — the site repeats the same file across several mirror rows.
    private fun parseReleases(doc: Document, season: Int, episode: Int): List<Release> {
        val itemSelector = if (season > 0) "#episodes .episode-download-item" else ".download-item"
        val fileSelector = if (season > 0) ".episode-file-title" else ".file-title"
        val grouped = LinkedHashMap<String, Release>()
        for (item in doc.select(itemSelector)) {
            val filename = item.selectFirst(fileSelector)?.text()?.trim().orEmpty()
            if (filename.isEmpty() || isArchive(filename)) continue
            if (season > 0) {
                val se = SE_EP_REGEX.find(filename) ?: continue
                if (se.groupValues[1].toIntOrNull() != season ||
                    se.groupValues[2].toIntOrNull() != episode
                ) continue
            }
            val mirrors = item.select("a[href]").mapNotNull { link ->
                val href = link.attr("href")
                if (!href.startsWith("https://") || "logout" in href) return@mapNotNull null
                Mirror(label = link.text().trim().ifEmpty { "Source" }, url = href)
            }
            if (mirrors.isEmpty()) continue
            val release = grouped.getOrPut(normalizeFilename(filename)) {
                Release(filename = filename, quality = qualityInt(filename), mirrors = mutableListOf())
            }
            for (mirror in mirrors) {
                if (release.mirrors.none { it.url == mirror.url }) release.mirrors += mirror
            }
        }
        return grouped.values.sortedByDescending { it.quality }
    }

    // HubCloud: /drive/ page → a#download → landing page → pixeldrain ids scraped
    // from the raw html (they live in scripts, not anchors) + every valid anchor.
    private suspend fun resolveHubcloud(driveUrl: String): List<Candidate> {
        requireHubcloud(driveUrl)
        val driveDoc = app.get(driveUrl, headers = headers).document
        val landingUrl = driveDoc.select("a#download")
            .map { it.attr("href") }
            .firstOrNull { it.startsWith("https://") }
            ?: throw ErrorLoadingException("4KHDHub: hubcloud download link missing")
        val landing = app.get(landingUrl, headers = headers).text
        val candidates = extractPixeldrainUrls(landing).map { Candidate(it, "PixelDrain") }.toMutableList()
        for (link in Jsoup.parse(landing).select("a[href]")) {
            val url = validatePlaybackUrl(link.attr("href")) ?: continue
            val label = link.text().trim().ifEmpty { "Direct" }
            candidates += Candidate(pixeldrainApiUrl(url) ?: url, label)
        }
        return candidates.sortedBy { score(it.url, it.label) }.distinctBy { it.url }
    }

    // HubDrive: /file/ page → first anchor into a hubcloud /drive/ → hubcloud flow.
    private suspend fun resolveHubdrive(driveUrl: String): List<Candidate> {
        val uri = runCatching { URI(driveUrl) }.getOrNull()
            ?: throw ErrorLoadingException("4KHDHub: bad hubdrive url")
        val ok = uri.scheme == "https" && uri.host?.contains("hubdrive.") == true &&
            (uri.path ?: "").startsWith("/file/")
        if (!ok) throw ErrorLoadingException("4KHDHub: not a hubdrive file url")

        val doc = app.get(driveUrl, headers = headers).document
        val hubcloudUrl = doc.select("a[href]").mapNotNull { link ->
            val href = link.attr("href")
            val target = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
            val host = target.host ?: return@mapNotNull null
            if (host.contains("hubcloud.") && (target.path ?: "").startsWith("/drive/")) href else null
        }.firstOrNull() ?: throw ErrorLoadingException("4KHDHub: hubdrive has no hubcloud mirror")
        return resolveHubcloud(hubcloudUrl)
    }

    private fun requireHubcloud(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw ErrorLoadingException("4KHDHub: bad hubcloud url")
        val ok = uri.scheme == "https" && uri.host?.contains("hubcloud.") == true &&
            (uri.path ?: "").startsWith("/drive/")
        if (!ok) throw ErrorLoadingException("4KHDHub: not a hubcloud drive url")
    }

    // greenmotors-family ad wrapper: the ?id= page carries an obfuscated blob
    // (base64 → base64 → rot13 → base64 → flat JSON). "o" is the real target
    // (often itself base64), "l" is the ad decoy, {data,blog_url} is an unlock
    // variant whose target is the body of blog_url?re=data. Port of the funnel
    // used by other CS3 providers (JustPlayNet.decryptIdLink).
    private suspend fun resolveWrapped(wrapUrl: String): List<Candidate> {
        val target = decryptIdLink(wrapUrl) ?: return emptyList()
        return when {
            "hubcloud." in target -> resolveHubcloud(target)
            "hubdrive." in target -> resolveHubdrive(target)
            else -> validatePlaybackUrl(target)?.let { listOf(Candidate(it, "Direct")) }
                ?: emptyList()
        }
    }

    private suspend fun decryptIdLink(url: String): String? {
        val text = app.get(url, headers = headers).text
        val concat = (BLOB_REGEX.findAll(text) + WP_BLOB_REGEX.findAll(text))
            .joinToString("") { it.groupValues[1] }
        if (concat.isEmpty()) return null
        val decoded = runCatching {
            val inner = String(
                android.util.Base64.decode(
                    android.util.Base64.decode(concat, B64_FLAGS),
                    B64_FLAGS
                ),
                Charsets.ISO_8859_1
            )
            String(android.util.Base64.decode(rot13(inner), B64_FLAGS), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val json = decoded.replace("\\/", "/")

        // unlock variant: the wrapper wants a round-trip through its blog page
        val blog = jsonField(json, "blog_url")
        val data = jsonField(json, "data")
        if (blog != null && data != null && blog.startsWith("http")) {
            val res = runCatching {
                app.get("$blog?re=$data", headers = headers, allowRedirects = false)
            }.getOrNull() ?: return null
            return res.document.body().text().trim().takeIf { it.startsWith("http") }
        }

        val target = jsonField(json, "o").takeIf { !it.isNullOrBlank() }
            ?: jsonField(json, "l")?.takeIf { it.startsWith("http") }
            ?: return null
        val url = if (target.startsWith("http")) target
        else runCatching {
            String(android.util.Base64.decode(target, B64_FLAGS), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return url.trim().takeIf { it.startsWith("http") }
    }

    private fun rot13(value: String): String = buildString {
        for (c in value) append(
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        )
    }

    // value of "key":"..." in a flat wrapper JSON (already \/ -unescaped)
    private fun jsonField(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun extractPixeldrainUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        for (prefix in PIXELDRAIN_PREFIXES) {
            var start = 0
            while (true) {
                val at = html.indexOf(prefix, start)
                if (at < 0) break
                val candidate = html.substring(at)
                val end = candidate.indexOfFirst {
                    it == '"' || it == '\'' || it.isWhitespace() || it == '<' || it == '\\'
                }.let { if (it < 0) candidate.length else it }
                pixeldrainApiUrl(candidate.substring(0, end))?.let { if (it !in urls) urls += it }
                start = at + end.coerceAtLeast(1)
            }
        }
        return urls
    }

    // Any pixeldrain /u/ or /api/file/ url → canonical api/file/<id>?download form.
    private fun pixeldrainApiUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.contains("pixeldrain.")) return null
        val path = uri.path ?: return null
        val id = when {
            path.startsWith("/u/") -> path.removePrefix("/u/")
            path.startsWith("/api/file/") -> path.removePrefix("/api/file/")
            else -> return null
        }.trim('/')
        if (id.isEmpty() || !PIXELDRAIN_ID.matches(id)) return null
        return "https://$host/api/file/$id?download"
    }

    // Lower = better mirror host (pixeldrain first), same ranking as hubcloud.rs.
    private fun score(url: String, label: String): Int {
        val value = "$url $label".lowercase()
        return when {
            "pixeldrain" in value || "pixel.hubcloud" in value -> 0
            "gpdl." in value || "googleusercontent" in value -> 1
            "workers.dev" in value || "r2.dev" in value -> 2
            "latent.click" in value || "fsl" in value -> 3
            else -> 4
        }
    }

    // Port of client.rs preflight: 1-byte range probe; an html/zip/plain response
    // means we landed on a wrapper page — follow its ?link= param once and re-probe.
    // Range bytes=0-0 (Rust uses 0-): NiceHttp doesn't close bodies, so cap the probe.
    private suspend fun preflight(url: String, referer: String): String? {
        val probeHeaders = mapOf(
            "Referer" to referer,
            "User-Agent" to BROWSER_UA,
            "Range" to "bytes=0-0",
        )
        var (finalUrl, type) = probe(url, probeHeaders) ?: return null
        if (isWrapperType(type)) {
            // some wrappers put the raw target in ?link= unencoded (raw google
            // urls contain '+' that URLDecoder would turn into spaces), some
            // percent-encode it — try the raw value first, decoded second
            val linkValue = runCatching { URI(finalUrl) }.getOrNull()
                ?.rawQuery?.split('&')
                ?.mapNotNull { param ->
                    val i = param.indexOf('=')
                    if (i <= 0) null else param.substring(0, i) to param.substring(i + 1)
                }
                ?.firstOrNull { it.first == "link" }?.second
                ?: return null
            val wrapped = listOfNotNull(
                linkValue,
                linkValue?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() },
            ).firstOrNull { it.startsWith("https://") && validatePlaybackUrl(it) != null }
                ?: return null
            val second = probe(wrapped, probeHeaders) ?: return null
            finalUrl = second.first
            if (isWrapperType(second.second)) return null
        }
        return finalUrl
    }

    private fun isWrapperType(contentType: String): Boolean =
        contentType.contains("text/html") || contentType.contains("application/zip") ||
            contentType.contains("text/plain")

    private suspend fun probe(url: String, probeHeaders: Map<String, String>): Pair<String, String>? {
        if (validatePlaybackUrl(url) == null) return null
        val res = runCatching { app.get(url, headers = probeHeaders) }.getOrNull() ?: return null
        if (!res.isSuccessful) return null
        val finalUrl = res.url.toString()
        if (validatePlaybackUrl(finalUrl) == null) return null
        // okhttp3.Headers.get is case-insensitive
        val contentType = res.headers["Content-Type"]?.lowercase().orEmpty()
        return finalUrl to contentType
    }

    // Port of hubcloud.rs validate_playback_url. IP-literal hosts only — no DNS
    // resolve (same as Rust). ponytail: IPv6 gets a prefix check, not full is_public_ip.
    // The site emits hrefs with spaces/brackets that Rust's Url::parse auto-encoded;
    // Java's URI rejects them, so percent-encode the illegal set first and hand the
    // encoded form downstream.
    private fun validatePlaybackUrl(raw: String): String? {
        val encoded = encodeUnsafeUrlChars(raw.trim())
        val uri = runCatching { URI(encoded) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val path = (uri.path ?: "").lowercase()
        if (host == "localhost" || host.endsWith(".local") || isPrivateIpLiteral(host)) return null
        if (path.endsWith(".zip") || "login.php" in path || "logout" in path) return null
        return encoded
    }

    private fun encodeUnsafeUrlChars(url: String): String = buildString {
        for (c in url) {
            when {
                c.code in 0x21..0x7e && c !in "[]<>\\^`{}|\"" -> append(c)
                c == ' ' -> append("%20")
                c.code > 0x7e -> for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                    append("%02X".format(b.toInt() and 0xFF))
                }
                else -> append("%${"%02X".format(c.code)}")
            }
        }
    }

    private fun isPrivateIpLiteral(host: String): Boolean {
        val h = host.trim('[', ']')
        if (h.contains(':')) { // IPv6 literal: loopback, unspecified, ula, link-local
            return h == "::" || h == "::1" ||
                h.startsWith("fc") || h.startsWith("fd") ||
                h.startsWith("fe8") || h.startsWith("feb")
        }
        if (!h.all { it in '0'..'9' || it == '.' }) return false // hostname, not an IP
        val octets = h.split('.')
        if (octets.size != 4) return true // numeric junk — treat as unsafe
        val v = octets.map { it.toIntOrNull() ?: return true }
        return v[0] == 0 || v[0] == 10 || v[0] == 127 ||
            (v[0] == 172 && v[1] in 16..31) || (v[0] == 192 && v[1] == 168) ||
            (v[0] == 169 && v[1] == 254) ||
            (v[0] == 192 && v[1] == 0 && v[2] == 2) ||
            (v[0] == 198 && v[1] == 51 && v[2] == 100) ||
            (v[0] == 203 && v[1] == 0 && v[2] == 113) ||
            (v[0] == 255 && v[1] == 255 && v[2] == 255 && v[3] == 255)
    }

    // --- ports of the Rust parser.rs text helpers ---

    private fun firstFourDigitYear(value: String): Int? = YEAR_REGEX.find(value)?.value?.toIntOrNull()

    private fun stripTrailingYear(value: String): String =
        value.trim().replace(Regex("""\(\d{4}\)$"""), "").trimEnd()

    // Rust's fixed ["2160p","1080p","720p","480p"] precedence as a when.
    private fun qualityInt(filename: String): Int {
        val lower = filename.lowercase()
        return when {
            "2160p" in lower -> 2160
            "1080p" in lower -> 1080
            "720p" in lower -> 720
            "480p" in lower -> 480
            else -> 0
        }
    }

    private fun isArchive(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".zip") || "complete season" in lower || "season pack" in lower
    }

    private fun normalizeFilename(value: String): String =
        value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    // ".metadata-item" blocks: label text (e.g. "Release:") → ".metadata-value" text
    private fun Document.metadata(label: String): String? =
        select(".metadata-item").firstNotNullOfOrNull { item ->
            item.selectFirst(".metadata-label")?.text()?.trim()
                ?.takeIf { it == label }
                ?.let { item.selectFirst(".metadata-value")?.text()?.trim()?.takeIf(String::isNotEmpty) }
        }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val YEAR_REGEX = Regex("""[12]\d{3}""")
        val SE_EP_REGEX = Regex("""(?i)S(\d+)E(\d+)""")
        val NUM_REGEX = Regex("""\d+(?:\.\d+)?""")
        val PIXELDRAIN_ID = Regex("""[A-Za-z0-9_-]+""")
        // greenmotors-family wrapper blobs: s('o','B64') or ck('_wp_http_N','B64')
        val BLOB_REGEX = Regex("""s\('o','([A-Za-z0-9+/=]+)'""")
        val WP_BLOB_REGEX = Regex("""ck\('_wp_http_\d+','([^']+)'""")
        const val B64_FLAGS = android.util.Base64.DEFAULT
        val PIXELDRAIN_PREFIXES = listOf(
            "https://pixeldrain.dev/u/",
            "https://pixeldrain.com/u/",
            "https://pixeldrain.dev/api/file/",
            "https://pixeldrain.com/api/file/",
        )
        val GENRES = setOf(
            "action", "adventure", "animation", "comedy", "crime", "documentary", "drama",
            "family", "fantasy", "history", "horror", "music", "mystery", "romance",
            "science fiction", "sci-fi", "thriller", "war", "western",
        )
    }
}

private class Mirror(val label: String, val url: String)

private class Release(val filename: String, val quality: Int, val mirrors: MutableList<Mirror>)

private class Candidate(val url: String, val label: String)
