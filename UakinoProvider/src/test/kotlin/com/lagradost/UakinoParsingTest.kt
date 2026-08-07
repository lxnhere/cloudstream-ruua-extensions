package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Test

class UakinoParsingTest {
    @Test
    fun `episode label parses dashed season-episode`() {
        assertEquals(UakinoSeasonEpisode(1, 3), parseUakinoEpisodeLabel("Серія 1-3"))
        assertEquals(UakinoSeasonEpisode(2, 1), parseUakinoEpisodeLabel("Серія 2–1"))
    }

    @Test
    fun `episode label parses plain episode with page season later`() {
        assertEquals(UakinoSeasonEpisode(null, 12), parseUakinoEpisodeLabel("Серія 12"))
    }

    @Test
    fun `page season is read from URL and title`() {
        assertEquals(
            9,
            parseUakinoPageSeason(
                "https://uakino.best/cartoon/cartoonseries/34181-rik-ta-morti-9-sezon.html",
                "Рік та Морті 9 сезон",
            ),
        )
        assertEquals(37, parseUakinoPageSeason("https://x/30136-simpsony-37-sezon.html", ""))
    }

    @Test
    fun `sibling season filter keeps same show and drops spinoffs`() {
        assertEquals(
            true,
            uakinoIsSiblingSeason(
                "Рік та Морті",
                "Рік та Морті",
                "https://uakino.best/cartoon/cartoonseries/5547-rk-ta-mort-1-sezon.html",
            ),
        )
        assertEquals(
            false,
            uakinoIsSiblingSeason(
                "Сімпсони",
                "Сімпсони: Короткометражки з шоу Трейсі Уллман",
                "https://uakino.best/cartoon/cartoonseries/34505-simpsony-korotkometrazhky-1-sezon.html",
            ),
        )
    }

    @Test
    fun `player URL keeps a valid HTTPS scheme`() {
        assertEquals("https://video.example/player", normalizeUakinoPlayerUrl("http://video.example/player"))
        assertEquals("https://video.example/player", normalizeUakinoPlayerUrl("//video.example/player"))
    }

    @Test
    fun `episode name keeps commas after the data separator`() {
        val parsed = parseUakinoEpisodeData("https://uakino.best/player,Серія 1, розширена версія")

        assertEquals("https://uakino.best/player", parsed.requestUrl)
        assertEquals("Серія 1, розширена версія", parsed.episodeName)
    }

    @Test
    fun `invalid year falls back to the existing default`() {
        assertEquals(2023, parseUakinoYear("невідомо", 2023))
    }

    @Test
    fun `movie fallback uses the original detail page`() {
        val movieUrl = "https://uakino.best/filmy/family/35377-toni-10.html"
        val ajaxUrl = "https://uakino.best/engine/ajax/playlists.php?news_id=35377"

        // Для фільму AJAX-відповідь може бути ERR_NOT_DATA, тому сторінку треба
        // повторно завантажувати за початковим URL, а не за URL AJAX-запиту.
        assertEquals(movieUrl, resolveUakinoDetailUrl(movieUrl, null, ajaxUrl))
    }

    @Test
    fun `episode fallback keeps the player request URL`() {
        val movieUrl = "https://uakino.best/filmy/family/35377-toni-10.html"
        val ajaxUrl = "https://uakino.best/engine/ajax/playlists.php?news_id=35377"

        assertEquals(ajaxUrl, resolveUakinoDetailUrl(movieUrl, "Серія 1", ajaxUrl))
    }

    @Test
    fun `tortuga file is decoded to a playable HLS URL`() {
        val encrypted =
            "tqu+pais3MLbmGNlaWdtSgJHVTM8OjE8ShwGH6/v4uaz19jQ0dOjv6f0kYaFg5ZafHJoSndNX1ojLiAGDAgAEhnx1c7t8cXYwOTz8Onugd3dxMwuYGNlMk1FVlw4aSNmKVs==="

        assertEquals(
            "https://calypso.tortuga.wtf/hls/trailers/south_park_bigger_longer__uncut_1999_8176/hls/index.m3u8",
            resolveUakinoStreamUrl(encrypted),
        )
    }

    @Test
    fun `invalid encrypted file is ignored`() {
        assertEquals(null, resolveUakinoStreamUrl("not-a-playable-file"))
    }
}
