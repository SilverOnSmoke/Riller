// Use an integer for version numbers — bump on every change
version = 5

cloudstream {
    description = "Riller — multi-source movie & series scrapers"
    authors = listOf("riller")

    /**
     * 0: Down, 1: Ok, 2: Slow, 3: Beta-only
     * NOTE: status 0 (Down) makes the app unload/refuse to load the plugin entirely
     * (PluginManager: isDisabled = status == PROVIDER_STATUS_DOWN) — must be >= 1.
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}
