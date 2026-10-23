version = 1

dependencies {
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    description = "Moviestape.lat — кіно українською (Playerjs / progressive MP4)."
    authors = listOf("lxnhere")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon",
    )
    iconUrl = "https://www.google.com/s2/favicons?domain=moviestape.lat&sz=%size%"
}
