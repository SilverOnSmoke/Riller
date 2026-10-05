package com.riller.sources

import com.lagradost.cloudstream3.TvType
import com.riller.core.RillerSource

// TODO(cinejoy): confirm real domain, then implement search()/getMainPage()/load() in this file.
class Cinejoy : RillerSource("Cinejoy", "https://cinejoy.example", TvType.Movie, TvType.TvSeries)
