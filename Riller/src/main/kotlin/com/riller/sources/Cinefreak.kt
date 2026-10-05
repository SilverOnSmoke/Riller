package com.riller.sources

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.riller.core.RillerSource
import java.net.URLDecoder

class Cinefreak : RillerSource("Cinefreak", "https://cinefreak.net/", TvType.Movie) {

    private val headers = mapOf("User-Agent" to BROWSER_UA)

    override suspend fun search(query: String): List<SearchResponse> {
        val res = tryParseJson<CfSearch>(
            app.get(
                "$mainUrl/search-api.php",
                headers = headers,
                params = mapOf(
                    "q" to query,
                    "pg" to "1",
                    "_t" to System.currentTimeMillis().toString(),
                ),
            ).text
        ) ?: return emptyList()

        return res.results.orEmpty().mapNotNull { item ->
            val slug = item.l?.trim('/').orEmpty()
            if (slug.isEmpty()) return@mapNotNull null
            val (title, year) = splitTitleYear(item.t.orEmpty())
            newMovieSearchResponse(title, "$mainUrl/$slug/", TvType.Movie) {
                posterUrl = item.i
                this.year = year?.toIntOrNull()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val slug = url.trimEnd('/').substringAfterLast('/')
        val (ogTitle, ogYear) = splitTitleYear(
            doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        )
        val title = ogTitle.ifBlank { titleFromSlug(slug).first }

        // Direct cinecloud /f/ page per print; subdomain rotates (new5 today)
        val links = F_URL_REGEX.findAll(doc.html()).map { it.value }.distinct().toList()

        val poster = runCatching {
            app.get(
                "$mainUrl/search-api.php",
                headers = headers,
                params = mapOf("q" to title, "_t" to System.currentTimeMillis().toString()),
            ).text
        }.getOrNull()?.let {
            tryParseJson<CfSearch>(it)?.results?.firstOrNull { r -> r.l?.trim('/') == slug }?.i
        }

        return newMovieLoadResponse(title, url, TvType.Movie, CfData(links)) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            this.year = ogYear?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fPages = tryParseJson<CfData>(data)?.links.orEmpty()

        val links = fPages.apmap { fUrl ->
            runCatching {
                val page = app.get(fUrl, headers = headers).text
                val link = R2_REGEX.find(page)?.value ?: return@apmap null
                val rawName = link.substringAfterLast('/')
                val filename = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
                newExtractorLink(
                    source = "Cinefreak",
                    name = filename,
                    url = link,
                    type = ExtractorLinkType.VIDEO, // direct file, no further resolution
                ) {
                    this.quality = qualityFromName(filename)
                }
            }.getOrNull()
        }.filterNotNull().sortedByDescending { it.quality }

        links.forEach(callback)
        return links.isNotEmpty()
    }

    private fun qualityFromName(filename: String): Int {
        val lower = filename.lowercase()
        val q = QUALITIES.firstOrNull { lower.contains(it) }
        return q?.dropLast(1)?.toIntOrNull() ?: Qualities.Unknown.value
    }

    // --- ports of the Rust text helpers (client.rs) ---

    private fun splitTitleYear(text: String): Pair<String, String?> {
        val trimmed = text.trim()
        val start = trimmed.lastIndexOf('(')
        if (start >= 0) {
            val end = trimmed.indexOf(')', start + 1)
            val inner = if (end > start) trimmed.substring(start + 1, end) else ""
            if (inner.length == 4 && inner.all { it.isDigit() }) {
                return trimmed.substring(0, start).trim().trimEnd('-').trim() to inner
            }
        }
        return trimmed to null
    }

    private fun titleFromSlug(slug: String): Pair<String, String?> {
        val parts = slug.split('-')
        val year = parts.lastOrNull()?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val body = if (year != null) parts.dropLast(1) else parts
        return body.joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } } to year
    }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        val F_URL_REGEX = Regex("https://[a-z0-9]+\\.cinecloud\\.site/f/[0-9a-f]+")
        val R2_REGEX = Regex("https://pub-[0-9a-f]+\\.r2\\.dev/[^\"'<>\\s]+")
        val QUALITIES = listOf("2160p", "1080p", "720p", "480p")
    }
}

private data class CfSearch(val results: List<CfItem>? = null)
private data class CfItem(val t: String? = null, val l: String? = null, val i: String? = null)
private data class CfData(val links: List<String> = emptyList())
