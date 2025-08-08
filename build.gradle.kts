dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "Arabic streaming site WeCima"
    authors = listOf("YourName")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")

    language = "ar"

    iconUrl = "https://wecima.show/favicon.ico"
}
