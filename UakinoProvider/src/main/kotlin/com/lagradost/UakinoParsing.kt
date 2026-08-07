package com.lagradost

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class UakinoEpisodeData(
    val requestUrl: String,
    val episodeName: String?,
)

/** Parsed S/E from Uakino playlist labels / season page URL. */
internal data class UakinoSeasonEpisode(
    val season: Int?,
    val episode: Int?,
)

internal fun normalizeUakinoPlayerUrl(rawUrl: String): String = when {
    rawUrl.startsWith("//") -> "https:$rawUrl"
    rawUrl.startsWith("http://") -> "https://${rawUrl.removePrefix("http://")}"
    else -> rawUrl
}

internal fun parseUakinoEpisodeData(data: String): UakinoEpisodeData {
    val separator = data.indexOf(',')
    if (separator < 0) return UakinoEpisodeData(data, null)

    return UakinoEpisodeData(
        requestUrl = data.substring(0, separator),
        episodeName = data.substring(separator + 1),
    )
}

internal fun resolveUakinoDetailUrl(
    originalData: String,
    targetEpisode: String?,
    requestUrl: String,
): String = if (targetEpisode == null) originalData else requestUrl

internal fun parseUakinoYear(rawYear: String, fallback: Int): Int =
    rawYear.trim().toIntOrNull() ?: fallback

/**
 * Playlist labels:
 * - "Серія 12" → episode 12 (season from page)
 * - "Серія 1-3" / "Серія 1–3" → season 1, episode 3
 */
internal fun parseUakinoEpisodeLabel(name: String): UakinoSeasonEpisode {
    val text = name.trim()
    val dashed = Regex(
        """^[Сс]ерія\s+(\d+)\s*[-–—]\s*(\d+)\s*$"""
    ).find(text)
    if (dashed != null) {
        return UakinoSeasonEpisode(
            season = dashed.groupValues[1].toIntOrNull(),
            episode = dashed.groupValues[2].toIntOrNull(),
        )
    }
    val plain = Regex("""^[Сс]ерія\s+(\d+)\s*$""").find(text)
    if (plain != null) {
        return UakinoSeasonEpisode(
            season = null,
            episode = plain.groupValues[1].toIntOrNull(),
        )
    }
    return UakinoSeasonEpisode(null, null)
}

/** URL `…-9-sezon.html` or title `… 9 сезон`. */
internal fun parseUakinoPageSeason(url: String, title: String = ""): Int? {
    Regex("""-(\d+)-sezon""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
        ?.toIntOrNull()?.let { return it }
    Regex("""(?:^|\s)(\d+)\s*[Сс]езон""").find(title)?.groupValues?.getOrNull(1)
        ?.toIntOrNull()?.let { return it }
    return null
}

/** Strip "N сезон" / spinoff noise for sibling-season search. */
internal fun uakinoSeriesBaseTitle(title: String): String {
    return title
        .replace(Regex("""\s*\d+\s*[Сс]езон.*$"""), "")
        .replace(Regex("""\s*[Сс]езон\s*\d+.*$"""), "")
        .trim()
}

internal fun uakinoNormalizeTitle(name: String): String {
    return name.lowercase()
        .replace(Regex("""[^a-z0-9а-яіїєґё]+""", RegexOption.IGNORE_CASE), "")
}

/** True when search hit is another season of the same show (not spinoff / Fortnite). */
internal fun uakinoIsSiblingSeason(
    baseTitle: String,
    hitTitle: String,
    hitUrl: String,
): Boolean {
    if (!hitUrl.contains("-sezon", ignoreCase = true)) return false
    if (hitUrl.contains("/franchise/", ignoreCase = true)) return false
    val spinoff = listOf(
        "короткометраж", "fortnite", "трейси", "ullman", "спіноф", "spin-off", "spinoff"
    )
    val low = hitTitle.lowercase()
    if (spinoff.any { it in low }) return false
    val base = uakinoNormalizeTitle(baseTitle)
    val hit = uakinoNormalizeTitle(uakinoSeriesBaseTitle(hitTitle))
    if (base.length < 4 || hit.length < 4) return false
    return hit.contains(base) || base.contains(hit) ||
        longestCommonSubstringLen(base, hit) >= minOf(6, base.length, hit.length)
}

private fun longestCommonSubstringLen(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var best = 0
    var prev = IntArray(b.length + 1)
    var cur = IntArray(b.length + 1)
    for (i in a.indices) {
        for (j in b.indices) {
            cur[j + 1] = if (a[i] == b[j]) prev[j] + 1 else 0
            if (cur[j + 1] > best) best = cur[j + 1]
        }
        val tmp = prev
        prev = cur
        cur = tmp
        cur.fill(0)
    }
    return best
}

/**
 * Розшифровує `file` з Tortuga-плеєра.
 * Перший байт є сіллю, решта байтів XOR-яться з (salt + 7*i + 13).
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeUakinoTortuga(encoded: String): String? {
    val clean = encoded.trim().replace(Regex("\\s"), "").trimEnd('=')
    if (clean.isBlank()) return null

    return try {
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val decoded = Base64.decode(padded)
        if (decoded.size < 2) return null

        val salt = decoded[0].toInt() and 0xFF
        val result = ByteArray(decoded.size - 1)
        for (i in 1 until decoded.size) {
            val key = (salt + 7 * (i - 1) + 13) % 256
            result[i - 1] = ((decoded[i].toInt() and 0xFF) xor key).toByte()
        }

        String(result, Charsets.UTF_8).takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun resolveUakinoStreamUrl(rawUrl: String): String? {
    val value = rawUrl.trim()
    if (value.isBlank()) return null
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    return decodeUakinoTortuga(value)
}
