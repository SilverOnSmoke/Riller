package com.riller.sources

import com.lagradost.cloudstream3.TvType
import com.riller.core.RillerSource

// TODO(moviebox): confirm real domain, then implement search()/getMainPage()/load() in this file.
// Types below are a guess until then — adjust to what the site actually serves.
class MovieBox : RillerSource("MovieBox", "https://moviebox.example", TvType.Movie, TvType.TvSeries)
