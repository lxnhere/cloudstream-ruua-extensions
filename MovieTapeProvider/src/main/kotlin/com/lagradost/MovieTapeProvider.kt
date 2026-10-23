package com.lagradost

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * Moviestape.lat — DLE catalog + Playerjs CDN (sniplyo/creavio) progressive MP4.
 * Voiceover is a single UA track described by page metadata «Переклад».
 */
class MovieTapeProvider : MainAPI() {
    override var mainUrl = "https://moviestape.lat"
    override var name = "MovieTape"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    private val gson = Gson()
    private val fileRegex = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""")
    private val plRegex = Regex("""pl\s*:\s*["'](https?://[^"']+/pl_new\.php\?pl=[^"']+)["']""")

    override val mainPage = mainPageOf(
        "/katalog_filmiv/page/" to "Фільми",
        "/katalog_serialiv/page/" to "Серіали",
        "/katalog_multfilmiv/page/" to "Мультфільми",
        "/anime/page/" to "Аніме",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl${request.data.removeSuffix("page/")}"
        } else {
            "$mainUrl${request.data}$page/"
        }
        val document = app.get(url).document
        val home = document.select("div.bnewmovie").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("p.title a") ?: this.selectFirst("a[href]") ?: return null
        val href = a.attr("href").ifBlank { return null }
        val title = a.text().ifBlank {
            this.selectFirst("p.title")?.text().orEmpty()
        }.ifBlank { return null }
        val poster = this.selectFirst("img")?.attr("src")?.let { absUrl(it) }
        return newMovieSearchResponse(title, absUrl(href), guessType(href)) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "utf-8")
        val json = app.get(
            "$mainUrl/engine/ajax/fastsearch.php?q=$encoded&scope=full&page=1&sort=relevance",
            headers = mapOf("Accept" to "application/json"),
        ).text
        val parsed = runCatching { gson.fromJson(json, FastSearch::class.java) }.getOrNull()
            ?: return emptyList()
        return parsed.results.orEmpty()
            .filter { it.resultType == "media" && !it.url.isNullOrBlank() && it.title != null }
            .filter { !(it.url!!.contains("/treilers/") || it.url.contains("/treiler")) }
            .map {
                newMovieSearchResponse(it.title!!, it.url!!, guessType(it.url!!)) {
                    this.posterUrl = it.image
                    this.year = it.year
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.f-poster2 img, .f-poster img, meta[property=og:image]")
            ?.let { it.attr("src").ifBlank { it.attr("content") } }
            ?.let { absUrl(it) }
        val translation = document.selectFirst("div.f-content2, .f-content")
            ?.html()
            ?.let { html ->
                Regex(
                    """Переклад:</strong>\s*([^<]+)""",
                    RegexOption.IGNORE_CASE,
                ).find(html)?.groupValues?.get(1)?.trim()
            }
            .orEmpty()
            .ifBlank { "Український" }
        val description = document.selectFirst("div.f-content2_s, .full-text, .f-content2 p")
            ?.text()?.trim()
        val year = Regex("""\((\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst("strong:contains(Рік)")?.parent()?.text()
                ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val iframeSrc = document.selectFirst("iframe#pl")?.attr("src")?.trim().orEmpty()
        val sourceLabel = "MovieTape · $translation"
        val tvType = guessType(url, iframeSrc)

        if (iframeSrc.contains("stream.php?pl=") || iframeSrc.contains("pl=")) {
            val embed = app.get(iframeSrc, referer = mainUrl).text
            val playlistUrl = plRegex.find(embed)?.groupValues?.get(1)
                ?: iframeSrc.replace("stream.php?", "pl_new.php?")
            val episodes = parsePlaylistEpisodes(playlistUrl, sourceLabel)
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = listOf(translation)
            }
        }

        // Movie: store iframe URL; resolve MP4 in loadLinks
        return newMovieLoadResponse(title, url, TvType.Movie, "$sourceLabel|$iframeSrc") {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = listOf(translation)
        }
    }

    private suspend fun parsePlaylistEpisodes(
        playlistUrl: String,
        sourceLabel: String,
    ): List<Episode> {
        val text = app.get(playlistUrl, referer = mainUrl).text
        val root = runCatching { gson.fromJson(text, JsonArray::class.java) }.getOrNull()
            ?: return emptyList()
        val episodes = mutableListOf<Episode>()

        fun addEp(season: Int?, episode: Int?, name: String?, file: String) {
            if (file.isBlank()) return
            episodes.add(
                newEpisode("$sourceLabel|$file") {
                    this.name = name
                    this.season = season
                    this.episode = episode
                    this.data = "$sourceLabel|$file"
                }
            )
        }

        root.forEach { el ->
            val obj = el.asJsonObject
            val folder = obj.get("folder")
            if (folder != null && folder.isJsonArray) {
                val seasonTitle = obj.get("title")?.asString.orEmpty()
                val seasonNum = Regex("""(\d+)""").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.mapNotNull { it.season }.maxOrNull()?.plus(1) ?: 1)
                folder.asJsonArray.forEachIndexed { idx, epEl ->
                    val ep = epEl.asJsonObject
                    val epTitle = ep.get("title")?.asString.orEmpty()
                    val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (idx + 1)
                    val file = ep.get("file")?.asString.orEmpty()
                    addEp(seasonNum, epNum, epTitle.ifBlank { "Серія $epNum" }, file)
                }
            } else {
                val epTitle = obj.get("title")?.asString.orEmpty()
                val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)
                val file = obj.get("file")?.asString.orEmpty()
                addEp(1, epNum, epTitle.ifBlank { "Серія $epNum" }, file)
            }
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("|", limit = 2)
        if (parts.size < 2) return false
        val sourceName = parts[0]
        val payload = parts[1]
        val mp4 = when {
            payload.endsWith(".mp4", ignoreCase = true) || payload.contains("/video/") -> payload
            payload.contains("stream.php") -> {
                val embed = app.get(payload, referer = mainUrl).text
                fileRegex.find(embed)?.groupValues?.get(1).orEmpty()
            }
            else -> payload
        }
        if (mp4.isBlank() || !mp4.startsWith("http")) return false

        callback(
            newExtractorLink(
                source = name,
                name = sourceName,
                url = mp4,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private fun absUrl(path: String): String {
        if (path.startsWith("http")) return path
        return mainUrl + if (path.startsWith("/")) path else "/$path"
    }

    private fun guessType(url: String, iframe: String = ""): TvType {
        val u = url.lowercase()
        return when {
            u.contains("/katalog_serialiv/") || u.contains("/anime/") ||
                iframe.contains("stream.php?pl=") || iframe.contains("pl=") -> TvType.TvSeries
            u.contains("/katalog_multfilmiv/") -> TvType.Cartoon
            else -> TvType.Movie
        }
    }

    data class FastSearch(
        @SerializedName("results") val results: List<FastHit>? = null,
    )

    data class FastHit(
        @SerializedName("title") val title: String? = null,
        @SerializedName("url") val url: String? = null,
        @SerializedName("image") val image: String? = null,
        @SerializedName("year") val year: Int? = null,
        @SerializedName("result_type") val resultType: String? = null,
    )
}
