package com.wecima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class WeCimaProvider : MainPageAPI() {
    override var mainUrl = "https://wecima.show"
    override var name = "WeCima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/seriestv/top/" to "أشهر المسلسلات",
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/seriestv/" to "أحدث المسلسلات",
        "$mainUrl/list/anime/" to "أحدث الأنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + "?page_number=$page").document
        val home = document.select("div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = this.selectFirst("a > span.BG--GridItem")?.attr("data-lazy-style")
            ?.substringAfter("-image:url(")?.substringBefore(");")

        val type = when {
            href.contains("/series/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/search/$query/page/1/").document
        return searchResponse.select("div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = when {
            document.selectFirst("li:contains(المسلسل) p") != null -> {
                document.select("li:contains(المسلسل) p").text()
            }
            document.selectFirst("singlerelated.hasdivider:contains(سلسلة) a") != null -> {
                document.selectFirst("singlerelated.hasdivider:contains(سلسلة) a")!!.text()
            }
            else -> {
                document.select("div.Title--Content--Single-begin > h1").text()
                    .substringBefore(" (").replace("مشاهدة فيلم ", "").substringBefore("مترجم")
            }
        }

        val poster = document.selectFirst("div.Poster--Single-begin img")?.attr("src")
        val description = document.selectFirst("div.AsideContext > div.StoryMovieContent")?.text()
        val genre = document.select("li:contains(التصنيف) > p > a, li:contains(النوع) > p > a")
            .map { it.text() }
        val year = document.selectFirst("li:contains(سنة الإنتاج) p")?.text()?.toIntOrNull()

        // Check if it's a series or movie
        val episodes = document.select("div.Episodes--Seasons--Episodes a")
        
        return if (episodes.isNotEmpty() || document.select("div.List--Seasons--Episodes a").isNotEmpty()) {
            // It's a series
            val episodesList = getEpisodesList(document)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
            }
        } else {
            // Check for movie series
            val movieSeries = document.select("singlerelated.hasdivider:contains(سلسلة) div.Thumb--GridItem a")
            if (movieSeries.isNotEmpty()) {
                val episodesList = movieSeries.mapIndexed { index, element ->
                    Episode(
                        data = element.absUrl("href"),
                        name = element.text().replace("مشاهدة فيلم ", "").substringBefore("مترجم"),
                        episode = index + 1
                    )
                }
                newTvSeriesLoadResponse(title, url, TvType.Movie, episodesList) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = genre
                    this.year = year
                }
            } else {
                // It's a single movie
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = genre
                    this.year = year
                }
            }
        }
    }

    private suspend fun getEpisodesList(document: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seasonsList = document.select("div.List--Seasons--Episodes a")
        
        if (seasonsList.isEmpty()) {
            // Single season
            document.select("div.Episodes--Seasons--Episodes a").forEach { element ->
                val episodeName = "الموسم 1 : ${element.text()}"
                val episodeUrl = element.absUrl("href")
                val episodeNumber = element.text().filter { it.isDigit() }.toIntOrNull() ?: 1
                
                episodes.add(
                    Episode(
                        data = episodeUrl,
                        name = episodeName,
                        season = 1,
                        episode = episodeNumber
                    )
                )
            }
        } else {
            // Multiple seasons
            seasonsList.reversed().forEachIndexed { seasonIndex, season ->
                val seasonNumber = season.text().filter { it.isDigit() }.toIntOrNull() ?: (seasonIndex + 1)
                
                val seasonDocument = if (season.hasClass("selected")) {
                    document
                } else {
                    app.get(season.absUrl("href")).document
                }
                
                seasonDocument.select("div.Episodes--Seasons--Episodes a").forEach { element ->
                    val episodeName = "الموسم $seasonNumber : ${element.text()}"
                    val episodeUrl = element.absUrl("href")
                    val episodeNumber = element.text().filter { it.isDigit() }.toIntOrNull() ?: 1
                    
                    episodes.add(
                        Episode(
                            data = episodeUrl,
                            name = episodeName,
                            season = seasonNumber,
                            episode = episodeNumber
                        )
                    )
                }
            }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        document.select("ul.WatchServersList li").forEach { server ->
            val serverUrl = server.selectFirst("btn")?.attr("data-url") ?: return@forEach
            val serverName = server.text().lowercase()
            
            when {
                server.hasClass("MyCimaServer") && "/run/" in serverUrl -> {
                    val mp4Url = serverUrl.replace("?Key", "/?Key") + "&auto=true"
                    callback.invoke(
                        ExtractorLink(
                            "WeCima",
                            "WeCima Server",
                            mp4Url,
                            referer = "$mainUrl/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                }
                "govid" in serverName || "vidbom" in serverName || "vidshare" in serverName -> {
                    loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)
                }
                "dood" in serverName -> {
                    loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)
                }
                "ok.ru" in serverName -> {
                    loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)
                }
                "uqload" in serverName -> {
                    loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
        
        return true
    }
}
