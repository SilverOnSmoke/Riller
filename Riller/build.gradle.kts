// Use an integer for version numbers — bump on every change
version = 3

cloudstream {
    description = "Riller — multi-source movie & series scrapers"
    authors = listOf("riller")

    /**
     * 0: Down, 1: Ok, 2: Slow, 3: Beta-only
     */
    status = 0 // flip to 1 once at least one source returns playable links

    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}
