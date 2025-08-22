// CloudStream v3 provider skeleton for tv2.egydead.live // Notes: // - You said you have permission from the site owner. This provider scrapes only publicly served pages. // - The site appears to be WordPress-based and exposes categories, search, and post pages. // - You may need to tweak selectors if the theme changes. // // Place this file in: app/src/main/java/com/yourname/cloudstream/providers/EgyDeadProvider.kt // Then register it in the extension module's AndroidManifest and build.gradle.kts as usual.

package com.yourname.cloudstream.providers

import com.lagradost.cloudstream3.* import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId import com.lagradost.cloudstream3.extractors.helper.* import com.lagradost.cloudstream3.utils.* import org.jsoup.nodes.Document import org.jsoup.nodes.Element import java.net.URLEncoder

class EgyDeadProvider : MainAPI() { override var mainUrl = "https://tv2.egydead.live" override var name = "EgyDead (TV2)" override val hasMainPage = true override var lang = "ar" override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)

private val homeSections = listOf(
    HomePageList("أحدث الأفلام", "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A/"),
    HomePageList("أحدث الحلقات", "$mainUrl/page/1/"), // fallback to homepage if no dedicated feed
    HomePageList("أحدث المواسم", "$mainUrl/page/1/")
)

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val pages = ArrayList<HomePageList>()
    // 1) Homepage blocks: parse cards under sections like "# احدث الافلام" etc.
    runCatching {
        val doc = app.get(mainUrl).document
        val latestMovies = doc.select("a:containsOwn(مشاهدة فيلم)")
            .mapNotNull { toSearchResponse(it) }
        if (latestMovies.isNotEmpty()) pages += HomePageList("أحدث الأفلام", latestMovies)

        val latestEpisodes = doc.select("a:matchesOwn(الحلقة|حلقه)")
            .mapNotNull { toSearchResponse(it) }
        if (latestEpisodes.isNotEmpty()) pages += HomePageList("أحدث الحلقات", latestEpisodes)

        val latestSeasons = doc.select("a:containsOwn(مترجم كامل)")
            .mapNotNull { toSearchResponse(it) }
        if (latestSeasons.isNotEmpty()) pages += HomePageList("أحدث المواسم", latestSeasons)
    }
    // 2) Category (paginated)
    val catUrl = homeSections.first().url + (if (page > 1) "page/$page/" else "")
    val catDoc = app.get(catUrl).document
    val catItems = catDoc.select("article a[href*='/%D9%85%D8%B4%D8%A7%D9%87%D8%AF%D8%A9-'], .entry-content a:containsOwn(مشاهدة)")
        .mapNotNull { toSearchResponse(it) }
    if (catItems.isNotEmpty()) pages += HomePageList("افلام اجنبي", catItems)

    return newHomePageResponse(pages)
}

private fun guessPoster(a: Element): String? {
    // Try image sibling or data-bg style
    val img = a.parent()?.selectFirst("img[src]") ?: a.selectFirst("img[src]")
    return img?.attr("abs:src")
}

private fun toSearchResponse(a: Element): SearchResponse? {
    val href = a.absUrl("href")
    if (!href.startsWith(mainUrl)) return null
    val title = a.text().trim().ifEmpty { a.attr("title") }
    val poster = guessPoster(a)
    val isEpisode = title.contains("الحلقة") || title.contains("حلقه")
    return if (isEpisode) {
        newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    } else {
        newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}

override suspend fun search(query: String): List<SearchResponse> {
    // WordPress search: ?s=
    val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
    val doc = app.get(url).document
    // Grab typical post cards
    val anchors = doc.select(".post a[href*='/%D9%85%D8%B4%D8%A7%D9%87%D8%AF%D8%A9-'], article a[href*='/%D9%85%D8%B4%D8%A7%D9%87%D8%AF%D8%A9-']")
    return anchors.mapNotNull { toSearchResponse(it) }
        .distinctBy { it.url }
}

override suspend fun load(url: String): LoadResponse? {
    val doc = app.get(url).document
    val title = doc.selectFirst("h1,
        .entry-title, 
        meta[property=og:title]")?.let { it.attr("content").ifEmpty { it.text() } }?.trim() ?: return null
    val poster = doc.selectFirst(".post img, .entry-content img, meta[property=og:image]")
        ?.let { it.attr("content").ifEmpty { it.attr("abs:src") } }
    val plot = doc.selectFirst(".post p, .entry-content p")?.text()
    val year = Regex("(19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

    // Detect if page is a series (has episodes) or a single movie
    val hasEpisodes = doc.select("a:matchesOwn(الحلقة|حلقه)").isNotEmpty()

    return if (hasEpisodes) {
        val episodes = ArrayList<Episode>()
        // Basic strategy: episode links appear as anchors with episode number labels
        doc.select("a:matchesOwn(الحلقة|حلقه)").forEach { epA ->
            val epUrl = epA.absUrl("href")
            val epName = epA.text().trim()
            val epNum = Regex("(\n|^| )(?<n>\\d+)( |$)").find(epName)?.groups?.get("n")?.value?.toIntOrNull()
            episodes += Episode(epUrl, epName, episode = epNum)
        }
        newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    } else {
        newMovieLoadResponse(title, url, TvType.Movie, data = url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }
}

override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
    // Movie page: extract embedded players from the same page and any secondary subdomains (e.g., w*.egydead.live)
    val doc = app.get(data).document
    var found = false

    // 1) Direct iframes
    doc.select("iframe[src]").forEach { iframe ->
        val src = iframe.absUrl("src")
        found = loadExtractor(src, data, subtitleCallback, callback) || found
        // If the host is a custom subdomain like w8.egydead.live, try a shallow parse as fallback
        if (!found && src.contains("egydead", ignoreCase = true)) {
            found = parseCustomEgyDeadEmbed(src, subtitleCallback, callback) || found
        }
    }

    // 2) Buttons/data-urls (common pattern in WP themes)
    doc.select("a[data-url], button[data-url], li[data-url]").forEach { b ->
        val src = b.absUrl("data-url")
        if (src.isNotBlank()) {
            found = loadExtractor(src, data, subtitleCallback, callback) || found
            if (!found && src.contains("egydead", ignoreCase = true)) {
                found = parseCustomEgyDeadEmbed(src, subtitleCallback, callback) || found
            }
        }
    }

    return found
}

private fun parseCustomEgyDeadEmbed(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
    // Minimal fallback: try to find m3u8/mp4 inside the embed page
    val res = app.get(url)
    val body = res.text
    var ok = false
    Regex("https?:\\/\\/[^\"'\\s]+\\.m3u8").findAll(body).forEach { m ->
        callback(
            ExtractorLink(
                source = name,
                name = "EgyDead HLS",
                url = m.value,
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        ok = true
    }
    Regex("https?:\\/\\/[^\"'\\s]+\\.(mp4|mkv)").findAll(body).forEach { m ->
        callback(
            ExtractorLink(
                source = name,
                name = "EgyDead File",
                url = m.value,
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8 = false
            )
        )
        ok = true
    }
    // Basic subtitle hunt (VTT/SRT)
    Regex("https?:\\/\\/[^\"'\\s]+\\.(vtt|srt)").findAll(body).forEach { s ->
        subtitleCallback(SubtitleFile("ar", s.value))
    }
    return ok
}

}

