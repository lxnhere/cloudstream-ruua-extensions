// use an integer for version numbers
version = 1

cloudstream {
    language = "ru"
    description = "Filmix RU streams with per-studio voiceovers. Ported from open-source resoul/filmix (Swift)."
    authors = listOf("lxnhere", "resoul")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=filmix.gg&sz=%size%"
}
