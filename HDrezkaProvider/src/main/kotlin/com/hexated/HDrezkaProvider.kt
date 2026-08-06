package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.*

/**
 * HDrezka — tokenless scrape of public mirrors (n0madic/go-hdrezka defaults + extras).
 * Picks the first reachable mirror at runtime and refreshes paths against it.
 */
class HDrezkaProvider : MainAPI() {
    override var mainUrl = "https://hdrezka.ag"
    override var name = "HDrezka"
    override val hasMainPage = true
    override var lang = "ru"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    companion object {
        /** Open-source defaults from n0madic/go-hdrezka + common public mirrors. */
        private val MIRRORS = listOf(
            "https://hdrezka.ag",
            "https://rezka.ag",
            "https://hdrzk.org",
            "https://hdrezka.co",
            "https://rezka-ua.in",
        )
    }

    @Volatile
    private var mirrorReady = false

    private suspend fun ensureWorkingMirror() {
        if (mirrorReady) return
        for (mirror in MIRRORS) {
            try {
                val base = mirror.trimEnd('/')
                val doc = app.get(base, timeout = 12_000).document
                val ok = doc.selectFirst("div.b-content__inline_items, #search, form#search") != null
                    || doc.select("div.b-content__inline_item").isNotEmpty()
                    || doc.selectFirst("a[href*=/films/], a[href*=/series/]") != null
                if (ok) {
                    mainUrl = base
                    mirrorReady = true
                    return
                }
            } catch (_: Exception) {
                // try next
            }
        }
        // Keep constructor default if nothing answered
        mirrorReady = true
    }

    private fun pageUrl(pathQuery: String): String {
        val p = pathQuery.trim()
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return try {
                val u = java.net.URI(p)
                val path = u.rawPath ?: "/"
                val q = u.rawQuery?.let { "?$it" }.orEmpty()
                "$mainUrl$path$q"
            } catch (_: Exception) {
                p.replace(Regex("""https?://[^/]+"""), mainUrl)
            }
        }
        return mainUrl + if (p.startsWith("/")) p else "/$p"
    }

    // Paths only — host resolved via [ensureWorkingMirror]
    override val mainPage = mainPageOf(
        "/films/?filter=last" to "фильмы — новинки",
        "/films/?filter=watching" to "фильмы — смотрят",
        "/films/?filter=popular" to "фильмы — популярные",
        "/series/?filter=last" to "сериалы — новинки",
        "/series/?filter=watching" to "сериалы — смотрят",
        "/series/?filter=popular" to "сериалы — популярные",
        "/cartoons/?filter=last" to "мультфильмы — новинки",
        "/cartoons/?filter=watching" to "мультфильмы — смотрят",
        "/animation/?filter=last" to "аниме — новинки",
        "/animation/?filter=watching" to "аниме — смотрят",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        ensureWorkingMirror()
        val parts = request.data.split("?", limit = 2)
        val path = parts.first()
        val query = parts.getOrNull(1)?.let { "?$it" }.orEmpty()
        val home = app.get(pageUrl("${path}page/$page/$query")).document.select(
            "div.b-content__inline_items div.b-content__inline_item"
        ).map {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title =
            this.selectFirst("div.b-content__inline_item-link > a")?.text()?.trim().toString()
        val href = this.selectFirst("a")?.attr("href").toString()
        val posterUrl = this.select("img").attr("src")
        val year = this.selectFirst("div.b-content__inline_item-link > div")?.text()
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""(?<![0-9])((?:19|20)\d{2})(?![0-9])""").findAll(href)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.lastOrNull()
        val type = if (this.select("span.info").isNotEmpty()) TvType.TvSeries else TvType.Movie
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            val episode =
                this.select("span.info").text().substringAfter(",").replace(Regex("[^0-9]"), "")
                    .toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                addDubStatus(
                    dubExist = true,
                    dubEpisodes = episode,
                    subExist = true,
                    subEpisodes = episode
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureWorkingMirror()
        val link = "$mainUrl/search/?do=search&subaction=search&q=$query"
        val document = app.get(link).document

        return document.select("div.b-content__inline_items div.b-content__inline_item").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureWorkingMirror()
        val resolved = pageUrl(url)
        val document = app.get(resolved).document

        val id = resolved.split("/").last().split("-").first()
        val title = (document.selectFirst("div.b-post__origtitle")?.text()?.trim()
            ?: document.selectFirst("div.b-post__title h1")?.text()?.trim()).toString()
        val poster = fixUrlNull(document.selectFirst("div.b-sidecover img")?.attr("src"))
        val tags =
            document.select("table.b-post__info > tbody > tr:contains(Жанр) span[itemprop=genre]")
                .map { it.text() }
        val year = document.select("div.film-info > div:nth-child(2) a").text().toIntOrNull()
        val tvType = if (document.select("div#simple-episodes-tabs")
                .isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("div.b-post__description_text")?.text()?.trim()
        val trailer = app.post(
            "$mainUrl/engine/ajax/gettrailervideo.php",
            data = mapOf("id" to id),
            referer = resolved
        ).parsedSafe<Trailer>()?.code.let {
            Jsoup.parse(it.toString()).select("iframe").attr("src")
        }
        val rating =
            document.selectFirst("table.b-post__info > tbody > tr:nth-child(1) span.bold")?.text()
        val actors =
            document.select("table.b-post__info > tbody > tr:last-child span.item").mapNotNull {
                Actor(
                    it.selectFirst("span[itemprop=name]")?.text() ?: return@mapNotNull null,
                    it.selectFirst("span[itemprop=actor]")?.attr("data-photo")
                )
            }

        val recommendations = document.select("div.b-sidelist div.b-content__inline_item").map {
            it.toSearchResult()
        }

        val data = HashMap<String, Any>()
        val server = ArrayList<Map<String, String>>()

        data["id"] = id
        data["favs"] = document.selectFirst("input#ctrl_favs")?.attr("value").toString()
        data["ref"] = resolved

        return if (tvType == TvType.TvSeries) {
            document.select("ul#translators-list li").map { res ->
                val node = res.selectFirst("a[data-translator_id]") ?: res
                server.add(
                    mapOf(
                        "translator_name" to (node.attr("title").ifBlank { node.text() }),
                        "translator_id" to node.attr("data-translator_id"),
                    )
                )
            }
            val episodes = document.select("div#simple-episodes-tabs ul li").map {
                val season = it.attr("data-season_id").toIntOrNull()
                val episode = it.attr("data-episode_id").toIntOrNull()
                val name = "Episode $episode"

                val episodeData = HashMap<String, Any>(data).apply {
                    this["season"] = "$season"
                    this["episode"] = "$episode"
                    this["server"] = server
                    this["action"] = "get_stream"
                }

                newEpisode(episodeData.toJson(), {
                    this.name = name
                    this.season = season
                    this.episode = episode
                }, fix = false)
            }

            newTvSeriesLoadResponse(title, resolved, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            document.select("ul#translators-list li").map { res ->
                val node = res.selectFirst("a[data-translator_id]") ?: res
                server.add(
                    mapOf(
                        "translator_name" to (node.attr("title").ifBlank { node.text() }),
                        "translator_id" to node.attr("data-translator_id"),
                        "camrip" to node.attr("data-camrip"),
                        "ads" to node.attr("data-ads"),
                        "director" to node.attr("data-director")
                    )
                )
            }

            data["server"] = server
            data["action"] = "get_movie"

            newMovieLoadResponse(title, resolved, TvType.Movie, data.toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    private fun decryptStreamUrl(data: String): String {
        // Newer mirrors sometimes return cleartext "[720p]https://... ,[1080p]https://..."
        // instead of the legacy #h / //_// trash-encoded blob.
        val trimmed = data.trim()
        if (trimmed.contains("[") && trimmed.contains("http") && !trimmed.contains("#h")) {
            return trimmed.replace("\\/", "/")
        }

        fun getTrash(arr: List<String>, item: Int): List<String> {
            val trash = ArrayList<List<String>>()
            for (i in 1..item) {
                trash.add(arr)
            }
            return trash.reduce { acc, list ->
                val temp = ArrayList<String>()
                acc.forEach { ac ->
                    list.forEach { li ->
                        temp.add(ac.plus(li))
                    }
                }
                return@reduce temp
            }
        }

        val trashList = listOf("@", "#", "!", "^", "$")
        val trashSet = getTrash(trashList, 2) + getTrash(trashList, 3)
        var trashString = data.replace("#h", "").split("//_//").joinToString("")

        trashSet.forEach {
            val temp = base64Encode(it.toByteArray())
            trashString = trashString.replace(temp, "")
        }

        return try {
            base64Decode(trashString)
        } catch (_: Exception) {
            // Last resort: treat as cleartext if decode fails
            trimmed.replace("\\/", "/")
        }
    }

    private suspend fun cleanCallback(
        source: String,
        url: String,
        quality: String,
        isM3u8: Boolean,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        sourceCallback.invoke(
            newExtractorLink(
                source,
                source,
                url,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = getQuality(quality)
                this.headers = mapOf(
                    "Origin" to mainUrl
                )
            }
        )
    }

    private fun getLanguage(str: String): String {
        return when (str) {
            "Русский" -> "Russian"
            "Українська" -> "Ukrainian"
            else -> str
        }
    }

    private fun getQuality(str: String): Int {
        return when (str) {
            "360p" -> Qualities.P240.value
            "480p" -> Qualities.P360.value
            "720p" -> Qualities.P480.value
            "1080p" -> Qualities.P720.value
            "1080p Ultra" -> Qualities.P1080.value
            else -> getQualityFromName(str)
        }
    }

    private suspend fun invokeSources(
        source: String,
        url: String,
        subtitle: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        for (links in decryptStreamUrl(url).split(",")) {
            val quality =
                Regex("\\[([0-9]{3,4}p\\s?\\w*?)]").find(links)?.groupValues?.getOrNull(1)
                    ?.trim() ?: continue
            for (raw in links.replace("[$quality]", "").split(" or ")) {
                val link = raw.trim()
                val type = if (link.contains(".m3u8")) "Main" else "Backup"
                // "Studio • 1080p (Main)" — voiceover picker parses studio before quality
                cleanCallback(
                    "$source • $quality ($type)",
                    link,
                    quality,
                    link.contains(".m3u8"),
                    sourceCallback,
                )
            }
        }

        for (sub in subtitle.split(",")) {
            val language =
                Regex("\\[(.*)]").find(sub)?.groupValues?.getOrNull(1) ?: continue
            val link = sub.replace("[$language]", "").trim()
            subCallback.invoke(
                newSubtitleFile(
                    getLanguage(language),
                    link
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureWorkingMirror()

        tryParseJson<Data>(data)?.let { res ->
            if (res.server?.isEmpty() == true) {
                val document = app.get(res.ref ?: return@let).document
                document.select("script").map { script ->
                    if (script.data().contains("sof.tv.initCDNMoviesEvents(")) {
                        val dataJson =
                            script.data().substringAfter("false, {").substringBefore("});")
                        tryParseJson<LocalSources>("{$dataJson}")?.let { source ->
                            invokeSources(
                                this.name,
                                source.streams,
                                source.subtitle.toString(),
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            } else {
                res.server?.amap { server ->
                    app.post(
                        url = "$mainUrl/ajax/get_cdn_series/?t=${Date().time}",
                        data = mapOf(
                            "id" to res.id,
                            "translator_id" to server.translator_id,
                            "favs" to res.favs,
                            "is_camrip" to server.camrip,
                            "is_ads" to server.ads,
                            "is_director" to server.director,
                            "season" to res.season,
                            "episode" to res.episode,
                            "action" to res.action,
                        ).filterValues { it != null }.mapValues { it.value as String },
                        referer = res.ref
                    ).parsedSafe<Sources>()?.let { source ->
                        invokeSources(
                            server.translator_name.toString(),
                            source.url,
                            source.subtitle.toString(),
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }

        return true
    }

    data class LocalSources(
        @JsonProperty("streams") val streams: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Sources(
        @JsonProperty("url") val url: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Server(
        @JsonProperty("translator_name") val translator_name: String?,
        @JsonProperty("translator_id") val translator_id: String?,
        @JsonProperty("camrip") val camrip: String?,
        @JsonProperty("ads") val ads: String?,
        @JsonProperty("director") val director: String?,
    )

    data class Data(
        @JsonProperty("id") val id: String?,
        @JsonProperty("favs") val favs: String?,
        @JsonProperty("server") val server: List<Server>?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("action") val action: String?,
        @JsonProperty("ref") val ref: String?,
    )

    data class Trailer(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("code") val code: String?,
    )

}
