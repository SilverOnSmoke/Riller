# Cloudstream Extension Developer Notes

Compiled from primary sources only:
- Docs site: https://recloudstream.github.io/csdocs/ (source repo: https://github.com/recloudstream/csdocs)
- Official repos linked by the docs: https://github.com/recloudstream/TestPlugins (the official plugin template), https://github.com/recloudstream/cloudstream, https://github.com/recloudstream/gradle, https://github.com/recloudstream/extensions, https://github.com/Blatzar/NiceHttp
- Dokka API reference: https://recloudstream.github.io/dokka/library/com.lagradost.cloudstream3/

Doc pages read (all pages under `/csdocs/devs/` — the sidebar contains more than the two linked):
`devs/gettingstarted`, `devs/using-plugin-template`, `devs/create-your-own-providers`, `devs/create-your-own-json-repository`, `devs/scraping/gettingstarted`, `devs/scraping/starting`, `devs/scraping/using_apis`, `devs/scraping/devtools_detectors`, `devs/scraping/disguising_your_scraper`, `devs/scraping/finding_video_links`.

> IMPORTANT repo-name correction: the task's guessed template `recloudstream/extension-template` returns GitHub 404. The docs' "Using plugin template" page (https://recloudstream.github.io/csdocs/devs/using-plugin-template/) points to **https://github.com/recloudstream/TestPlugins** ("Cloudstream3 Plugin Repo Template"). The gradle-plugin README also links `recloudstream/plugin-template`, which is also 404. TestPlugins is the only live official template.

---

## 1. Project setup

(source: using-plugin-template, TestPlugins README + build files, create-your-own-json-repository)

### Steps (docs, verbatim)
1. Fork the Test Plugins repo (https://github.com/recloudstream/TestPlugins/fork).
2. Check GitHub actions are enabled: `Settings > Actions > General > Allow all actions and reusable workflows`.
3. Make sure workflows have push access: `Settings > Actions > General > Read and write permissions`.
4. Create your own plugins; every push is automatically built. README warning: "Make sure you check 'Include all branches' when using this template".

### Template structure (TestPlugins, tree verbatim)
```
.github/workflows/build.yml
ExampleProvider/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/kotlin/com/example/ExamplePlugin.kt
  src/main/kotlin/com/example/ExampleProvider.kt
  src/main/kotlin/com/example/BlankFragment.kt
  src/main/res/... (drawable, layout, values, values-pl)
build.gradle.kts          (root)
settings.gradle.kts       (root)
gradle.properties
gradle/wrapper/*
gradlew, gradlew.bat
README.md
```
Each plugin is a subdirectory with its own `build.gradle.kts`; `settings.gradle.kts` auto-includes every dir containing one.

### Root `build.gradle.kts` (TestPlugins, verbatim)
```kotlin
import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // These dependencies can include any of those which are added by the app,
        // but you don't need to include any of them if you don't need them.
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts
        implementation(kotlin("stdlib")) // Adds Standard Kotlin Features
        implementation("com.github.Blatzar:NiceHttp:0.4.11") // HTTP Lib
        implementation("org.jsoup:jsoup:1.18.3") // HTML Parser
        // IMPORTANT: Do not bump Jackson above 2.13.1, as newer versions will
        // break compatibility on older Android devices.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // JSON Parser
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
```
Notes on the exact coordinates (source: TestPlugins build.gradle.kts, verified against recloudstream/gradle repo):
- Gradle plugin classpath: `com.github.recloudstream:gradle:-SNAPSHOT` (JitPack, SNAPSHOT of master of https://github.com/recloudstream/gradle).
- Gradle plugin id applied: `com.lagradost.cloudstream3.gradle`.
- Cloudstream API stubs: configuration `cloudstream("com.lagradost:cloudstream3:pre-release")` — i.e. the plugin builds against the **pre-release** app, not stable.
- `apiVersion` is fixed at `1` inside `CloudstreamExtension` (`val apiVersion = 1` in recloudstream/gradle `CloudstreamExtension.kt`) and is stamped into the generated `.cs3` manifest / plugins.json. Developers do not set it.

### Root `settings.gradle.kts` (TestPlugins, verbatim)
```kotlin
rootProject.name = "CloudstreamPlugins"

// This file sets what projects are included.
// All new projects should get automatically included unless specified in the "disabled" variable.

val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

// To only include a single project, comment out the previous lines (except the first one), and include your plugin like so:
// include("PluginName")
```

### `gradle.properties` (TestPlugins, key lines verbatim)
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=true
```

### Per-plugin `ExampleProvider/build.gradle.kts` (TestPlugins, verbatim)
```kotlin
dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Lorem ipsum"
    authors = listOf("Cloudburst", "Luna712")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Movie")

    requiresResources = true
    language = "en"

    // Random CC logo I found
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/Korduene_Logo.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
```
All `cloudstream { }` block properties (source: recloudstream/gradle `CloudstreamExtension.kt`): `apiVersion` (val, =1, read-only), `setRepo(...)`, `buildBranch` (default `"builds"`), `requiresResources`, `description`, `authors`, `status` (default 3), `language`, `tvTypes`, `iconUrl`, `isCrossPlatform`. `setRepo` accepts github / gitlab / codeberg / `gitlab-<domain>` / `gitea-<domain>` / full url; raw-link formats are generated per host.

### Compile / run workflow
(source: TestPlugins README + .github/workflows/build.yml)
- Local build (output is a `.cs3` file per plugin under `<plugin>/build/`):
  - Windows: `.\gradlew.bat ExampleProvider:make` (also `:deployWithAdb`)
  - Linux & Mac: `./gradlew ExampleProvider:make` (also `:deployWithAdb`)
- Local testing on Android 11+ requires "All Files Access" for the Cloudstream app:
  `adb shell appops set --uid PACKAGE_NAME MANAGE_EXTERNAL_STORAGE allow` with package names:
  - debug: `com.lagradost.cloudstream3.prerelease.debug`
  - prerelease: `com.lagradost.cloudstream3.prerelease`
  - stable: `com.lagradost.cloudstream3`
  (or manually via Settings > Special app access > All files access, then restart the app)
- CI (`.github/workflows/build.yml`) checks out the repo into `src` and a `builds` branch into `builds`, then runs:
  `./gradlew make makePluginsJson`, copies `**/build/*.cs3` and `build/plugins.json` into `builds`, and force-pushes the `builds` branch. Requires JDK 17.
- Install in app: add a repository whose JSON `pluginLists` points at `https://raw.githubusercontent.com/<user>/<repo>/builds/plugins.json` (see section 7). There is no "use this URL" WebView flow in the current docs; distribution is via repository JSON in app Settings > Extensions.

### Required app version
No explicit minimum version is stated in the docs. What is stated/implied: the template compiles against `com.lagradost:cloudstream3:pre-release` stubs, so target the **pre-release build** of Cloudstream; the plugin system (`.cs3` + plugins.json + manifestVersion 1) is the current one (old `.cs3`-free "extensions" system is gone).

---

## 2. MainAPI class

(source: csdocs create-your-own-providers + dokka MainAPI page; verified against `library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt` in recloudstream/cloudstream)

`abstract class MainAPI` — "Every provider will **not** have try catch built in, so handle exceptions when calling these functions".

All overridable properties (verbatim, with KDoc trimmed):
```kotlin
open var name = "NONE"                                  // shown in UI
open var mainUrl = "NONE"                               // replaceable via "Clone site" feature
open var storedCredentials: String? = null
open var canBeOverridden: Boolean = true
open var sequentialMainPage: Boolean = false            // request homepage lists one-by-one
open var sequentialMainPageDelay: Long = 0L             // ms, delay on first load
open var sequentialMainPageScrollDelay: Long = 0L       // ms, delay while scrolling
open var lang = "en"                                    // IETF BCP 47 tag
open val instantLinkLoading = false                     // links stored in episode "data"
open val hasChromecastSupport = true                    // false if links need referer / fail on chromecast
open val hasDownloadSupport = true                      // false if all links are encrypted
open val usesWebView = false                            // disable provider when WebView unavailable
open val hasMainPage = false
open val hasQuickSearch = false
open val loadLinksTimeoutMs: Long? = null               // hint only
open val getMainPageTimeoutMs: Long? = null
open val searchTimeoutMs: Long? = null
open val quickSearchTimeoutMs: Long? = null
open val loadTimeoutMs: Long? = null
open val supportedSyncNames = setOf<SyncIdName>()       // ids getLoadUrl() can open
open val supportedTypes = setOf(
    TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.OVA,
)
open val vpnStatus = VPNStatus.None
open val providerType = ProviderType.DirectProvider
open val mainPage = listOf(MainPageData("", "", false))
var sourcePlugin: String? = null                        // set by plugin system, not by you
```
Note: there is **no `mainPageFill` and no `hasChromecast`** in the current API — the real names are `hasChromecastSupport` and `mainPage`/`mainPageOf`.

All overridable methods (verbatim signatures):
```kotlin
open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse?
open suspend fun search(query: String, page: Int): SearchResponseList?   // paginated search, page starts at 1; default wraps search(query)
open suspend fun search(query: String): List<SearchResponse>?
open suspend fun quickSearch(query: String): List<SearchResponse>?
open suspend fun load(url: String): LoadResponse?
open suspend fun extractorVerifierJob(extractorData: String?)           // background job while a link plays (e.g. polling to keep link alive)
open suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean
open fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? // okhttp interceptor for OkHttpDataSource
open suspend fun getLoadUrl(name: SyncIdName, id: String): String?
```

### Enums and data classes
(source: MainAPI.kt)
```kotlin
enum class TvType(value: Int?) {
    Movie(1), AnimeMovie(2), TvSeries(3), Cartoon(4), Anime(5), OVA(6), Torrent(7),
    Documentary(8), AsianDrama(9), Live(10), NSFW(11), Others(12), Music(13),
    AudioBook(14), CustomMedia(15), Audio(16), Podcast(17), Video(18)
}
enum class VPNStatus { None, MightBeNeeded, Torrent }
enum class ProviderType { MetaProvider, DirectProvider }
enum class ShowStatus { Completed, Ongoing }
enum class DubStatus(val id: Int) { None(-1), Dubbed(1), Subbed(0) }
enum class SearchQuality(value: Int?)   // e.g. used for search-result quality marks
enum class ActorRole { Main, Supporting, Background }
```
`CustomMedia(15)`: "Won't load the built in player, make your own interaction".

```kotlin
data class MainPageData(val name: String, val data: String, val horizontalImages: Boolean = false)
data class MainPageRequest(val name: String, val data: String, val horizontalImages: Boolean)
data class HomePageList(val name: String, var list: List<SearchResponse>, val isHorizontalImages: Boolean = false)
data class HomePageResponse(val items: List<HomePageList>, val hasNext: Boolean = false)   // use newHomePageResponse()
data class SearchResponseList(val items: List<SearchResponse>, val hasNext: Boolean = false) // use newSearchResponseList()
```
Builders: `mainPage(url, name, horizontalImages = false)`, `mainPageOf(vararg Pair<String,String>)` (pair = (data/url, name)), `mainPageOf(vararg MainPageData)`, `newHomePageResponse(name|MainPageRequest|HomePageList, list, hasNext = null)`.

---

## 3. The full provider flow

(source: csdocs create-your-own-providers — all code below is from that page unless noted)

Docs: providers consist of 4 parts — Searching, Loading the home page, Loading the result page, Loading the video links. "When making a provider it is important that you are confident you can scrape the video links first! Video links are often the most protected part of the website and if you cannot scrape them then the provider is useless."

### 3.1 search()
```kotlin
// (code for Eja.tv)
override suspend fun search(query: String): List<SearchResponse> {
    return app.post(
        mainUrl, data = mapOf("search" to query) // Fetch the search data
    ).document // Convert the response to a searchable document
        .select("div.card-body") // Only select the search items using a CSS selector
        .mapNotNull { // Convert all html elements to SearchResponses and filter out the null search results
            it.toSearchResponse()
        }
}

// Converts a html element to a useable search response
private fun Element.toSearchResponse(): LiveSearchResponse? {
    val link = this.select("div.alternative a").last() ?: return null
    val href = link.attr("href")
    val img = this.selectFirst("div.thumb img")
    val lang = this.selectFirst(".card-title > a")?.attr("href")?.removePrefix("?country=")
        ?.replace("int", "eu") //international -> European Union

    // There are many types of searchresponses but mostly you will be using AnimeSearchResponse, MovieSearchResponse
    // and TvSeriesSearchResponse, all with different parameters (like episode count)
    return newLiveSearchResponse(
        img?.attr("alt")?.replaceFirst("Watch ", "") ?: return null,
        href,
        TvType.Live
    ) {
        this.posterUrl = fixUrl(img.attr("src"))
        this.lang = lang
    }
}
```
`fixUrl` is a built-in that converts relative urls like `/watch?v=...` to absolute.

### 3.2 getMainPage()
```kotlin
override val mainPage = mainPageOf(
        Pair("1", "Recent Release - Sub"),
        Pair("2", "Recent Release - Dub"),
        Pair("3", "Recent Release - Chinese"),
    )
```
```kotlin
// Gogoanime
override suspend fun getMainPage(
    page: Int,
    request : MainPageRequest
): HomePageResponse {
    val params = mapOf("page" to page.toString(), "type" to request.data)
    val html = app.get(
        "https://ajax.gogo-load.com/ajax/page-recent-release.html",
        headers = headers,
        params = params
    )
    val isSub = listOf(1, 3).contains(request.data.toInt())

    val home = parseRegex.findAll(html.text).map {
        val (link, epNum, title, poster) = it.destructured
        newAnimeSearchResponse(title, link) {
            this.posterUrl = poster
            addDubStatus(!isSub, epNum.toIntOrNull())
        }
    }.toList()

    return newHomePageResponse(request.name, home)
}
```
"page: An integer > 0, starts on 1 and counts up, Depends on how much the user has scrolled." The system exists to allow "infinite" loading. TLDR from docs: "Exactly like searching but you defined your own queries."

### 3.3 load() -> LoadResponse
Full SFlix-style example in the docs page; key excerpts:
```kotlin
// The url argument is the same as what you put in the Search Response from search() and getMainPage()
override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document
    val details = document.select("div.detail_page-watch")
    val img = details.select("img.film-poster-img")
    val posterUrl = img.attr("src")
    // It's safe to throw errors here, they will be shown to the user and can help debugging.
    val title = img.attr("title") ?: throw ErrorLoadingException("No Title")
    val rating = document.selectFirst(".fs-item > .imdb")?.text()?.trim()
        ?.removePrefix("IMDB:")?.toRatingInt()

    val isMovie = url.contains("/movie/")
    ...
    if (isMovie) {
        return newMovieLoadResponse(title, url, TvType.Movie, sourceIds) {
            this.year = year
            this.posterUrl = posterUrl
            this.plot = plot
            addDuration(duration)
            addActors(cast)
            this.tags = tags
            this.recommendations = recommendations
            this.comingSoon = comingSoon
            addTrailer(youtubeTrailer)
            this.rating = rating
        }
    } else {
        // seasons/episodes: fetch season dropdowns, then episodes per season, then:
        episodes.add(
            newEpisode(Pair(url, episodeData)) {
                this.posterUrl = fixUrlNull(episodePosterUrl)
                this.name = episodeTitle?.removePrefix("Episode $episodeNum: ")
                this.season = season + 1
                this.episode = episodeNum
            }
        )
        ...
    }
}
```
Docs notes: "It's safe to throw errors here, they will be shown to the user and can help debugging." / "Remember to always use OrNull functions otherwise stuff will throw exceptions on unexpected values." / "**NOTE**: Episodes in CloudStream are not paginated, meaning that if you have a show with 21 seasons, all on different website pages you will need to parse them all." / "The rating system goes is in the range 0 - 10 000 which allows the greatest flexibility app wise, but you will often need to multiply your values" (`toRatingInt()` helper).

### Exact builder signatures (source: MainAPI.kt in recloudstream/cloudstream)
```kotlin
// Search responses — all have (name, url, type, fix = true, initializer) shape
fun MainAPI.newMovieSearchResponse(name: String, url: String, type: TvType = TvType.Movie,
    fix: Boolean = true, initializer: MovieSearchResponse.() -> Unit = { }): MovieSearchResponse
fun MainAPI.newTvSeriesSearchResponse(...)   // analogous, with episodeCount on the class
fun MainAPI.newAnimeSearchResponse(...)      // analogous, plus dub/sub statuses on the class
fun MainAPI.newLiveSearchResponse(name: String, url: String, type: TvType = TvType.Live,
    fix: Boolean = true, initializer: LiveSearchResponse.() -> Unit = { }): LiveSearchResponse
fun MainAPI.newTorrentSearchResponse(name: String, url: String, type: TvType = TvType.Torrent,
    fix: Boolean = true, initializer: TorrentSearchResponse.() -> Unit = { }): TorrentSearchResponse

// Load responses
suspend fun <T> MainAPI.newMovieLoadResponse(
    name: String, url: String, type: TvType, data: T?,
    initializer: suspend MovieLoadResponse.() -> Unit = { }
): MovieLoadResponse            // `data` is JSON-encoded into dataUrl; comingSoon = dataUrl.isBlank()

suspend fun MainAPI.newMovieLoadResponse(
    name: String, url: String, type: TvType, dataUrl: String,
    initializer: suspend MovieLoadResponse.() -> Unit = { }
): MovieLoadResponse

suspend fun MainAPI.newTvSeriesLoadResponse(
    name: String, url: String, type: TvType, episodes: List<Episode>,
    initializer: suspend TvSeriesLoadResponse.() -> Unit = { }
): TvSeriesLoadResponse          // comingSoon = episodes.isEmpty()

suspend fun MainAPI.newAnimeLoadResponse(
    name: String, url: String, type: TvType, comingSoonIfNone: Boolean = true,
    initializer: suspend AnimeLoadResponse.() -> Unit = { }
): AnimeLoadResponse             // has episodes: MutableMap<DubStatus, List<Episode>>

suspend fun MainAPI.newLiveStreamLoadResponse(...)   // LiveStreamLoadResponse
suspend fun MainAPI.newTorrentLoadResponse(...)      // TorrentLoadResponse

fun <T> MainAPI.newEpisode(data: T, initializer: Episode.() -> Unit = { }): Episode
    // non-String data is JSON-encoded into Episode.data
fun MainAPI.newEpisode(url: String, initializer: Episode.() -> Unit = { }, fix: Boolean = true): Episode
```
`Episode` fields: `data` (passed to loadLinks), `name`, `season`, `episode`, `posterUrl`, `score`, `date`, `runTime` (seconds), plus addDate etc. helpers.
`SearchResponse` interface fields: `name: String`, `url: String`, `apiName: String`, `type: TvType?`, `posterUrl: String?`, `posterHeaders: Map<String,String>?`, `id: Int?`, `quality: SearchQuality?`, `score: Score?`.
`LoadResponse` interface fields (subset): `name`, `url`, `apiName`, `type: TvType`, `posterUrl`, `year: Int?`, `plot: String?`, `score: Score?`, `tags: List<String>?`, `duration: Int?` (minutes), `trailers: MutableList<TrailerData>`, `recommendations: List<SearchResponse>?`, `actors: List<ActorData>?`, `comingSoon: Boolean`, plus content-rating/season data on subtypes (`SeasonData`, `NextAiring`).
Initializer helpers used in docs: `addDuration(String?)`, `addActors(List<String>?)`, `addTrailer(String?)`, `addDubStatus(hasDub: Boolean, episodeCount: Int?)`, `toRatingInt()`, `fixUrl()`, `fixUrlNull()`, `apmapIndexed {}` (parallel map with index).

### 3.4 loadLinks()
```kotlin
open suspend fun loadLinks(
    data: String,                       // the string/JSON you put in newEpisode/newMovieLoadResponse
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean                              // return true if executed successfully
```
Inside, either build links directly with `newExtractorLink`/`callback` or call `loadExtractor(url, referer, subtitleCallback, callback)` for built-in hosts.

### 3.5 Loading links (docs guidance)
"This is usually the hardest part... video hosts have a big monetary incentive to make it as hard as possible... you cannot write just one piece of skeleton code to scrape all video hosts, they are all unique."
- **Base64**: "A dead giveaway that it is base64 or something similar is that the string ends with `==`"; also check any suspicious string mixing upper/lowercase A-z with numbers.
- **AES**: content is Base64 that "decodes to garbage"; look for references to `enc`, `iv` or `CryptoJS`; find the key with a browser debugger breakpoint.
- Page ends with "More to come later!" and a `# TODO: REST` heading — the page is unfinished by upstream.

---

## 4. Helper API (app / NiceHttp, document parsing, JSON)

(source: csdocs scraping/starting + using_apis; NiceHttp repo https://github.com/Blatzar/NiceHttp; cloudstream `library/.../MainActivity.kt` + `utils/AppUtils.kt`)

`app` is a global NiceHttp `Requests` instance (verbatim from cloudstream):
```kotlin
/** The default networking helper. This helper performs SSL checks.
 * If you need to make requests to websites with invalid SSL certificates use insecureApp instead. */
var app = Requests(responseParser = jsonResponseParser).apply {
    defaultHeaders = mapOf("user-agent" to USER_AGENT)
}
var insecureApp = Requests(...)   // ignores SSL certs; "should NEVER be used for sensitive networking operations such as logins"
```

### app.get / app.post signatures (NiceHttp `Requests.kt`, verbatim)
```kotlin
suspend fun get(
    url: String,
    headers: Map<String, String> = mapOf(),
    referer: String? = null,
    params: Map<String, String> = mapOf(),
    cookies: Map<String, String> = mapOf(),
    allowRedirects: Boolean = true,
    cacheTime: Int = defaultCacheTime,
    cacheUnit: TimeUnit = defaultCacheTimeUnit,   // default unit = minutes
    timeout: Long = defaultTimeOut,               // seconds
    interceptor: Interceptor? = null,
    verify: Boolean = true,                       // false -> ignore SSL errors
    responseParser: ResponseParser? = this.responseParser
): NiceResponse

suspend fun post(  // same params, plus:
    data: Map<String, String>? = defaultData,     // form body
    files: List<NiceFile>? = null,
    json: Any? = null,                            // JSON body (wrap string in JsonAsString("..."))
    requestBody: RequestBody? = null,
    ...
): NiceResponse
```
Also: `head`, `put`, `delete`, `patch`, `options`, `custom(method, ...)`.

`NiceResponse` methods used by docs: `.text`, `.document` (jsoup Document), `.parsed<T>()` (JSON via the response parser), `.selected(selector)` (css). Caching note from NiceHttp README: "For a request to be cached you need to consume the body! You do this by calling .text or .document".

### Document parsing (jsoup, docs examples)
`document.select("css")`, `document.selectFirst("css")`, `element.attr("href")`, `element.text()`, `element.ownText()`, `element?.attr(...) ?: return@forEach` null-safety pattern. Docs recommend CSS selectors + regex (`Regex(...).find(...)?.groupValues?.get(1)?.toIntOrNull()`), and generating data classes with json2kt / quicktype.

### JSON parsing
```kotlin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

inline fun <reified T : Any> parseJson(value: String): T
inline fun <reified T : Any> tryParseJson(value: String?): T?   // null on any exception
fun Any.toJson(): String                                        // serialize any object
```
Implementation detail (AppUtils.kt): kotlinx.serialization (with `@Serializable` classes) is preferred, falling back to the Jackson `mapper` (configured with `FAIL_ON_UNKNOWN_PROPERTIES=false`). Response `.parsed<T>()` uses the same stack. The docs' scraping/using_apis page teaches Jackson directly: `data class Planet(@JsonProperty("name") val name: String, ...)` and `JsonMapper.builder().addModule(KotlinModule()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()`; "Even though we set FAIL_ON_UNKNOWN_PROPERTIES as false it will still error on missing properties" — make optional keys nullable.

### AniList/TMDB helpers
The csdocs dev pages do not document these. The cloudstream repo contains metaproviders under `library/src/commonMain/kotlin/com/lagradost/cloudstream3/metaproviders/` (`TmdbProvider.kt`, `CrossTmdbProvider.kt`, `TraktProvider.kt`, `MyDramaList.kt`) and sync APIs under `syncproviders/` (`SyncAPI.kt`, `providers/AniListApi.kt`, `MALApi.kt`, `KitsuApi.kt`, `SimklApi.kt`) — read those files if you need them; they are not part of the documented tutorial path.

Other stdlib helpers: `fetchUrls(text): List<String>` (regex-extract all http urls), `getAndUnpack(string)` / `getPacked(string)` (deobfuscate `eval(function(p,a,c,k,e,...))` packers via JsUnpacker), `unshortenLinkSafe(url)`, `base64Decode`, `M3u8Helper` (in `utils/M3u8Helper.kt`), `apmap`/`apmapIndexed`/`amap` parallel collection helpers.

---

## 5. Extractors

(source: recloudstream/cloudstream `library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt` + `extractors/` package; registration via BasePlugin.kt)

### ExtractorApi abstract class (verbatim)
```kotlin
abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean

    var sourcePlugin: String? = null   // set by plugin system

    // new extractorapi — override this to add subtitles etc.
    @Throws
    open suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        getUrl(url, referer)?.forEach(callback)
    }

    @Throws
    open suspend fun getUrl(url: String, referer: String? = null): List<ExtractorLink>? {
        return emptyList()
    }

    suspend fun getSafeUrl(...)        // try/catch wrapper around getUrl
    open fun getExtractorUrl(id: String): String { return id }
}
```

### Minimal custom extractor (compiled from ExtractorApi.kt + newExtractorLink)
```kotlin
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MyHostExtractor : ExtractorApi() {
    override val name = "MyHost"                 // shown in player source list
    override val mainUrl = "https://myhost.example"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val link = doc.selectFirst("source")?.attr("src") ?: return

        callback(newExtractorLink(
            source = this.name,
            name = this.name,
            url = link,
            type = ExtractorLinkType.VIDEO       // or INFER_TYPE (null) to infer from url
        ) {
            this.referer = referer ?: ""
            this.quality = Qualities.P1080.value // Int; see Qualities below
            this.headers = mapOf("User-Agent" to "...")
        })
    }
}
```

### ExtractorLink (constructor params, verbatim doc-comment fields)
```kotlin
open class ExtractorLink(
    open val source: String,          // Name of the media source, appears on player layout
    open val name: String,            // Title of the media, appears on player layout
    open val url: String,
    open var referer: String,         // Referer used by the network request
    open var quality: Int,            // Quality of the media file
    open var headers: Map<String, String> = mapOf(),
    open var extractorData: String? = null,        // for getExtractorVerifierJob()
    open var type: ExtractorLinkType,              // VIDEO/M3U8/DASH/TORRENT/MAGNET
    open var audioTracks: List<AudioFile> = emptyList(),
) : IDownloadableMinimum {
    @get:JsonIgnore val isM3u8: Boolean get() = type == ExtractorLinkType.M3U8
    @get:JsonIgnore val isDash: Boolean get() = type == ExtractorLinkType.DASH
}
```
- `type = INFER_TYPE` (null) auto-infers from url. `isM3u8` is a derived property of `type`, not a constructor param anymore.
- `ExtractorLinkType` enum: `VIDEO` ("Single stream of bytes no matter the actual file type"), `M3U8` ("Split into several .ts files, has support for encrypted m3u8s"), `DASH` ("Like m3u8 but uses xml, currently no download support"), `TORRENT`, `MAGNET` ("No support at the moment").
- `newExtractorLink(source: String, name: String, url: String, type: ExtractorLinkType? = null, initializer: suspend ExtractorLink.() -> Unit = {})`.
- `newDrmExtractorLink(...)` exists for DRM (kid/key/uuid/kty/licenseUrl, CLEARKEY default).
- **Quality numbering convention** (source: `Qualities` enum): use `Qualities` values — `Unknown(400)`, `P144(144)`, `P240(240)`, `P360(360)`, `P480(480)`, `P720(720)`, `P1080(1080)`, `P1440(1440)`, `P2160(2160)` (= 4K). Player display: `0 -> "Auto"`, `400 -> "" (Unknown)`, `2160 -> "4K"`, else `"${quality}p"`. `getQualityFromName("4k") -> 2160`.

### Using built-in extractors: loadExtractor
```kotlin
suspend fun loadExtractor(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean

suspend fun loadExtractor(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean    // returns true if any extractor was loaded
```
Matching logic (verbatim behavior): unshortens the url, strips schema, then iterates the registered `extractorApis` list **in reverse ("new registered ExtractorApi takes priority")** matching `compareUrl.startsWith(extractor.mainUrl)`; if none, falls back to a Levenshtein `partialRatio(extractor.mainUrl, currentUrl) > 80` mirror-domain match.

Built-in extractors live in `library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/` in the cloudstream repo: Dood* family (DoodLaExtractor, DoodShExtractor, DoodWfExtractor, Dooood, ...), StreamWishExtractor, OkRuExtractor, VidhideExtractor, FileMoon/FileMoonIn/FileMoonSx/FilemoonV2, CloudMailRu, Dailymotion, Gofile, PixelDrain, SibNet, VkExtractor, UpstreamExtractor, HDMomPlayer, EmturbovidExtractor, RapidVid, YoutubeExtractor, and many more (full list = the `extractors/` dir). There is no curated "supported extractors" list in the csdocs docs themselves.

Register your extractor in the plugin (see section 6): `registerExtractorAPI(MyHostExtractor())`. Custom extractors registered later win over built-ins on the same mainUrl (reverse iteration order).

---

## 6. Conventions

(source: csdocs using-plugin-template, TestPlugins ExamplePlugin.kt/ExampleProvider.kt/build.gradle.kts comments)

### Plugin registration — the `main()` equivalent
There is no `main()`; a plugin class annotated `@CloudstreamPlugin` extending `Plugin()` with a `load(context)` override is the entrypoint. Verbatim template files:

`plugins/CloudstreamPlugin.kt` (cloudstream repo):
```kotlin
package com.lagradost.cloudstream3.plugins

@Suppress("unused")
@Target(AnnotationTarget.CLASS)
annotation class CloudstreamPlugin
```

`ExamplePlugin.kt` (TestPlugins, verbatim):
```kotlin
package com.example

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin: Plugin() {
    private var activity: AppCompatActivity? = null

    override fun load(context: Context) {
        activity = context as? AppCompatActivity

        // All providers should be added in this manner
        registerMainAPI(ExampleProvider())

        openSettings = {
            val frag = BlankFragment(this)
            activity?.let {
                frag.show(it.supportFragmentManager, "Frag")
            }
        }
    }
}
```
Registration API (source: `plugins/BasePlugin.kt`): `registerMainAPI(element: MainAPI)`, `registerExtractorAPI(element: ExtractorApi)`, `open fun beforeUnload()`, `open fun load()`, `var filename: String?` (full path to the .cs3). `openSettings = { ... }` adds a settings button in the app UI.

### Minimal provider skeleton (TestPlugins ExampleProvider.kt, verbatim)
```kotlin
package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

class ExampleProvider : MainAPI() { // All providers must be an instance of MainAPI
    override var mainUrl = "https://example.com/"
    override var name = "Example provider"
    override val supportedTypes = setOf(TvType.Movie)

    override var lang = "en"

    // Enable this when your provider has a main page
    override val hasMainPage = true

    // This function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        return listOf()
    }
}
```

### Naming / versioning / metadata
- Provider class name is free-form; `name` property is what the UI shows. Plugin filename = the `.cs3` file name.
- `version = 1` per plugin (must be an Int; bumped per release, ends up in manifest + plugins.json).
- `status` int (template comment, verbatim): `0: Down, 1: Ok, 2: Slow, 3: Beta-only`; "Will be 3 if unspecified" (gradle default `status = 3`). Note: the current documented set is 0/1/2/3 — there is no -1.
- `language = "en"` — IETF BCP 47 tag (MainAPI KDoc points to SubtitleHelper and the CLDR/IANA registries for the locale list).
- `tvTypes = listOf("Movie", "TvSeries", ...)` — string names of the TvType enum, used for app filtering.
- `iconUrl` — absolute https URL to a logo (no local icon requirement documented; `requiresResources = true` is what opts the plugin into bundling res/ files like drawables/layouts/strings).
- `authors = listOf("...")`, `description = "..."` optional.

### Testing / debugging workflow
1. `gradlew <PluginName>:make` -> `<PluginName>/build/<name>.cs3` (CI does the same plus `makePluginsJson`).
2. `gradlew <PluginName>:deployWithAdb` pushes to a connected device, or install/sideload the `.cs3` (app needs "All Files Access", see section 1).
3. Restart the Cloudstream app; plugins load on startup. `openSettings` gives an in-app settings dialog for debugging state.
4. Errors thrown inside `load()` are shown to the user ("It's safe to throw errors here, they will be shown to the user and can help debugging").
5. There is no documented emulator/desktop test harness; scraping is debugged in a browser first (docs scraping guide: devtools network tab, iFrame isolation, working backwards from the `.m3u8`/`.mp4` request).

---

## 7. Publishing

(source: csdocs create-your-own-json-repository; recloudstream/gradle MakePluginsJsonTask/CloudstreamExtension; recloudstream/extensions repo)

The docs' route is self-hosting a repository JSON — there is no documented PR flow into `recloudstream/extensions` (that repo's README only says: "This repository contains a collection of extensions for Cloudstream3", no contribution instructions).

### Repository JSON template (docs, verbatim)
```json
{
  "name": "<repository name>",
  "description": "<repository description>",
  "manifestVersion": 1,
  "pluginLists": [
    "<direct link to plugins.json>"
  ]
}
```
- `name`/`description`: "will be visible in the app".
- `manifestVersion`: "currently unused, may be used in the future for backwards compatibility".
- `pluginLists`: list of urls which contain plugins; "All of them will be fetched".
- If you followed the plugin-template tutorial, `plugins.json` is in the **builds branch** of your repo; otherwise generate one with `gradlew makePluginsJson`.
- App-side parsing (RepositoryManager.kt `Repository` data class) expects exactly: `iconUrl?`, `name`, `description?`, `manifestVersion: Int`, `pluginLists: List<String>`.
- `plugins.json` entries (`SitePlugin` data class) contain: `url` (direct .cs3 link), `status`, `version`, `apiVersion`, `name`, `internalName`, `authors`, `description?`, `repositoryUrl?`, `tvTypes?`, `language?`, `iconUrl?`, `fileSize?`, `fileHash?` — all generated by the gradle plugin (`apiVersion` is fixed to 1 by `CloudstreamExtension`, not set by you).
- You add the repository to the app by pasting the repository.json URL in the app's Extensions/repository settings (the docs' repository list page, `/Repositories`, now just links to the CloudStream wiki list of extensions).
- `resizeMode` is not part of the current plugin/repository JSON anywhere in the docs or app source — it does not exist in the modern system.
- The gradle `setRepo()` supports github, gitlab, codeberg, `gitlab-<domain>`, `gitea-<domain>`; raw-link formats are auto-derived (e.g. github: `https://raw.githubusercontent.com/%user%/%repo%/%branch%/%filename%`), and `buildBranch` defaults to `builds`.

---

## 8. Gotchas and warnings (all stated in docs/sources)

- **No automatic try/catch**: "Every provider will **not** have try catch built in, so handle exceptions when calling these functions" (MainAPI.kt).
- **Scrape video links first**: "if you cannot scrape them then the provider is useless" (create-your-own-providers).
- **Episodes are not paginated** — a 21-season show spread across pages must be fully parsed in `load()`.
- **Rating scale is 0–10000** — multiply your values, use `toRatingInt()`.
- **Null-safety**: "Remember to always use OrNull functions otherwise stuff will throw exceptions on unexpected values. We would not want a page to fail because the rating was incorrectly formatted."
- **JSON serialization**: `newMovieLoadResponse(data: T?)` and `newEpisode(data: T)` JSON-encode non-String `data` (`toJson()`), and it is decoded back before `loadLinks(data: String)` — pass anything JSON-encodable (Pair, data class, String). `parseJson<T>` prefers kotlinx.serialization (`@Serializable` classes) and falls back to Jackson; Jackson errors on *missing* properties even with `FAIL_ON_UNKNOWN_PROPERTIES=false`, so model optional keys as nullable (scraping/using_apis). AppUtils comment: "This is inlined code and can easily cause breakage in extensions!" (parseJson is inlined into your plugin — stable-app compatibility matters).
- **Jackson version lock**: "IMPORTANT: Do not bump Jackson above 2.13.1, as newer versions will break compatibility on older Android devices" (template build.gradle.kts).
- **Kotlin config is mandatory**: `jvmTarget.set(JvmTarget.JVM_1_8) // Required` plus `-Xno-call-assertions -Xno-param-assertions -Xno-receiver-assertions` (template).
- **Template usage**: check "Include all branches" when creating from the template, or the `builds` branch won't exist; enable Actions and give workflows read/write permissions.
- **Timeouts are hints**: `*TimeoutMs` properties "may not get respected if you request something too long".
- **Extractor priority**: newly registered extractors override built-ins (reverse-order matching by `mainUrl` prefix; mirror-domain fallback via Levenshtein > 80).
- **Caching only works if you consume the body** (`.text`/`.document`) (NiceHttp README).
- **Captcha sites**: 3 documented options — try a fake/empty token; use WebView to load the page; "Pray it's a captcha without payload" (scraping/finding_video_links).
- **Devtools detection**: sites detect open devtools (`debugger` loops, `console.log` `.toString()` tricks, `while(true)` loops); bypass with a web sniffer extension or the author's patched Firefox builds (scraping/devtools_detectors).
- **Headers to fake when disguising a scraper**: `User-Agent`, `Referer`, `X-Requested-With` (usually `XMLHttpRequest`), `Cookie`, `Authorization` (scraping/disguising_your_scraper). "Keep in mind that this is only the fraction of what the possible headers can be."
- **`lang` on SearchResponse vs MainAPI**: both exist; search-level lang (e.g. Eja.tv per-country) is separate from provider-level `lang`.
- **Docs are unfinished**: create-your-own-providers ends with "More to come later!" and `# TODO: REST`; TestPlugins README warns "This is currently under development, dont use it yet if you're not comfortable with constantly merging new changes".
- **Obfuscation spotting**: base64 strings often end `==`; AES shows up as base64 that decodes to garbage, with `enc`/`iv`/`CryptoJS` references nearby — find the key with a debugger.
