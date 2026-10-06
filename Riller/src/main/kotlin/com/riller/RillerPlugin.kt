package com.riller

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.riller.sources.Cinejoy
import com.riller.sources.FourKHdHub
import com.riller.sources.MovieBox

@CloudstreamPlugin
class RillerPlugin : Plugin() {
    override fun load(context: Context) {
        // Adding a source: create it in sources/ and register it here. One line each.
        registerMainAPI(MovieBox())
        registerMainAPI(Cinejoy())
        registerMainAPI(FourKHdHub())
    }
}
