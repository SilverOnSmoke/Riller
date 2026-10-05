package com.riller.core

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * Base class for every riller source: one subclass = one site = one provider in the app.
 *
 * Subclasses pass name/url/types via the constructor and implement search(),
 * getMainPage() and load() per site. loadLinks() already falls back to the app's
 * built-in extractors (Dood, StreamWish, FileMoon, OkRu, ...), so a source only
 * needs custom link-scraping for hosts Cloudstream doesn't cover.
 */
abstract class RillerSource(
    siteName: String,
    siteUrl: String,
    vararg types: TvType,
) : MainAPI() {
    override var name = siteName
    override var mainUrl = siteUrl
    override val supportedTypes = types.toSet()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = loadExtractor(data, subtitleCallback = subtitleCallback, callback = callback)
}
