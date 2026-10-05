package com.riller.sources

import com.lagradost.cloudstream3.TvType
import com.riller.core.RillerSource

// TODO(cinefreak): confirm real domain, then implement search()/getMainPage()/load() in this file.
class Cinefreak : RillerSource("Cinefreak", "https://cinefreak.example", TvType.Movie, TvType.TvSeries)
