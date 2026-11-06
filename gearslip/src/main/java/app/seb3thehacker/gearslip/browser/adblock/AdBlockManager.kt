package app.seb3thehacker.gearslip.browser.adblock

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.browser.BrowserSettings
import app.seb3thehacker.gearslip.browser.FilterList
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the filter lists: downloading them, keeping a parsed [FilterEngine] in memory, and
 * answering the two questions the WebView asks: block this request, and what to hide on
 * this page.
 *
 * Everything the request path touches is cached in memory. `shouldInterceptRequest` runs on a
 * background thread for every subresource on the page, so it must never read preferences or
 * touch disk. The switches come from [BrowserSettings]'s flows, which are plain memory reads.
 *
 * Downloading a list contacts the server it comes from (easylist.to for the two built in), and
 * nothing else: no page the driver visits is ever sent anywhere.
 */
object AdBlockManager {

    sealed class RefreshResult {
        data class Success(val networkRules: Int, val cosmeticRules: Int) : RefreshResult()
        data class Failure(val message: String) : RefreshResult()
    }

    private const val DIRECTORY = "adblock"
    private const val BUNDLED_ASSET = "adblock/bundled.txt"
    private const val MAX_LIST_BYTES = 12L * 1024 * 1024

    /**
     * How stale the lists may get before the browser refreshes them on its own. EasyList
     * publishes several times a day, but the parts that matter, the domains ads are served from,
     * turn over slowly, and each update costs a few megabytes.
     */
    private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    // 1x1 transparent GIF, returned for blocked images so pages show a gap rather than a
    // broken-image icon.
    private const val TRANSPARENT_GIF_BASE64 =
        "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"

    private val EMPTY = ByteArray(0)
    private val EMPTY_JSON = "{}".toByteArray()

    val transparentGifBytes: ByteArray by lazy {
        Base64.decode(TRANSPARENT_GIF_BASE64, Base64.DEFAULT)
    }

    @Volatile
    private var engine: FilterEngine? = null

    /** No per-site allowlist yet; kept so the request path reads the same as AABrowser's. */
    private val allowlist: Set<String> = emptySet()

    private val enabled: Boolean get() = BrowserSettings.adBlock.value
    private val youTubeEnabled: Boolean get() = BrowserSettings.youTubeAdBlock.value

    private val loadStarted = AtomicBoolean(false)
    private val refreshRunning = AtomicBoolean(false)
    private val autoUpdateAttempted = AtomicBoolean(false)
    private val blockedCount = AtomicLong(0)

    val isEngineReady: Boolean get() = engine != null
    val blockedRequestCount: Long get() = blockedCount.get()
    val networkRuleCount: Int get() = engine?.networkRuleCount ?: 0
    val cosmeticRuleCount: Int get() = engine?.cosmeticRuleCount ?: 0

    /** Loads the cached lists from disk once per process, on a background thread. */
    fun ensureInitialized(context: Context) {
        BrowserSettings.init(context)
        if (!enabled) return
        if (!loadStarted.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        Thread({
            runCatching { loadFromDisk(appContext) }
                .onFailure { GearslipLog.w("adblock: could not load the filter lists: ${it.message}") }
        }, "adblock-load").apply {
            // Blocking does nothing until this finishes, so it gets a normal share of the CPU.
            // On a Pixel 6 a release build parses both lists in about 250ms.
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    /** Rebuilds the engine from what's on disk after a list was turned on or off. */
    fun reload(context: Context) {
        val appContext = context.applicationContext
        loadStarted.set(true)
        Thread({ runCatching { loadFromDisk(appContext) } }, "adblock-load").start()
    }

    fun lastUpdatedAt(context: Context): Long =
        BrowserSettings.filterLists.value.mapNotNull { cacheFile(context, it).takeIf { file -> file.length() > 0 } }
            .maxOfOrNull { it.lastModified() } ?: 0L

    private fun cacheFile(context: Context, source: FilterList): File =
        File(File(context.filesDir, DIRECTORY), "${source.id}.txt")

    private fun loadFromDisk(context: Context) {
        val lists = BrowserSettings.filterLists.value.filter { it.enabled }
        val files = lists.map { cacheFile(context, it) }.filter { it.length() > 0 }
        val builder = FilterEngine.Builder()

        // A built-in list that hasn't downloaded yet comes from the snapshot in the APK, so
        // blocking works on the very first page rather than only after a download.
        val needsBundled = lists.any { it.builtIn && cacheFile(context, it).length() == 0L }
        if (needsBundled) {
            context.assets.open(BUNDLED_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    builder.addLine(line)
                }
            }
        }

        for (file in files) {
            file.bufferedReader(bufferSize = 64 * 1024).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    builder.addLine(line)
                }
            }
        }
        engine = builder.finish()
        GearslipLog.i("adblock: ${networkRuleCount} network and ${cosmeticRuleCount} hiding rules from ${lists.size} lists")
    }

    /** Downloads every list that's on, replaces the cached copies, and rebuilds the engine. */
    fun refreshLists(context: Context): RefreshResult {
        // The automatic refresh and the button in settings can both land here; two downloads
        // writing the same files would leave one of them truncated.
        val appContext = context.applicationContext
        if (!refreshRunning.compareAndSet(false, true)) {
            return RefreshResult.Failure("An update is already running.")
        }
        return try {
            refresh(appContext).also {
                // A manual update counts as a check, so the automatic one waits a full interval.
                if (it is RefreshResult.Success) BrowserSettings.lastUpdateCheck = System.currentTimeMillis()
            }
        } finally {
            refreshRunning.set(false)
        }
    }

    /**
     * Updates the lists when they have gone stale and the phone is on Wi-Fi, on a background
     * thread. Runs when the car's browser opens; there is no scheduled job. Only one attempt per
     * process, and the time is written on success alone, so a phone that was offline tries again
     * next time rather than in a week.
     */
    fun refreshIfDue(context: Context) {
        val appContext = context.applicationContext
        BrowserSettings.init(appContext)
        if (!enabled || !isUpdateDue(appContext) || !onUnmeteredNetwork(appContext)) return
        if (!autoUpdateAttempted.compareAndSet(false, true)) return
        Thread({
            val result = refreshLists(appContext)
            GearslipLog.i("adblock: weekly list update - $result")
        }, "adblock-update").start()
    }

    private fun isUpdateDue(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val lastCheck = BrowserSettings.lastUpdateCheck
        if (lastCheck > 0 && now - lastCheck < UPDATE_INTERVAL_MS) return false
        val baseline = lastUpdatedAt(context)
        // Nothing downloaded yet: the snapshot in the APK may be months old, so fetch now.
        if (baseline <= 0L) return true
        // A clock that has jumped backwards would otherwise freeze the lists indefinitely.
        if (baseline > now) return true
        return now - baseline >= UPDATE_INTERVAL_MS
    }

    private fun onUnmeteredNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun refresh(context: Context): RefreshResult {
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            return RefreshResult.Failure("Could not create the filter list folder.")
        }

        for (source in BrowserSettings.filterLists.value.filter { it.enabled }) {
            val result = runCatching { download(source, cacheFile(context, source)) }
            val error = result.exceptionOrNull()
            if (error != null) return RefreshResult.Failure("${source.title}: ${error.message ?: "download failed"}")
            val failure = result.getOrNull()
            if (failure != null) return RefreshResult.Failure("${source.title}: $failure")
        }

        loadStarted.set(true)
        val loadError = runCatching { loadFromDisk(context) }.exceptionOrNull()
        if (loadError != null) return RefreshResult.Failure(loadError.message ?: "Could not read the lists.")

        val current = engine ?: return RefreshResult.Failure("The lists downloaded but contained no rules.")
        return RefreshResult.Success(current.networkRuleCount, current.cosmeticRuleCount)
    }

    /** Returns null on success, or a human-readable reason for failure. */
    private fun download(source: FilterList, destination: File): String? {
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "text/plain")
        }
        try {
            if (connection.responseCode !in 200..299) return "HTTP ${connection.responseCode}"
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            var total = 0L

            temporary.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_LIST_BYTES) {
                            temporary.delete()
                            return "list is unexpectedly large"
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (total == 0L) {
                temporary.delete()
                return "empty response"
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                return "could not save the list"
            }
        } finally {
            connection.disconnect()
        }
        return null
    }

    /** Deletes the cached copy of a list the driver removed. */
    fun forget(context: Context, list: FilterList) {
        cacheFile(context, list).delete()
    }

    fun isAllowlisted(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val current = allowlist
        if (current.isEmpty()) return false
        val normalized = host.lowercase()
        return current.any { DomainUtils.hostMatchesDomain(normalized, it) }
    }

    /**
     * Decides a single subresource request and, if it should be blocked, builds the response for
     * it. [pageUrl] is the document the request belongs to, tracked by the WebView client because
     * a WebView's own url can only be read on the UI thread. Null means let the request through.
     */
    fun response(request: WebResourceRequest, pageUrl: String?): WebResourceResponse? {
        if (!enabled) return null
        val current = engine ?: return null

        // Never interfere with top-level navigation: a mistake there costs the whole page.
        if (request.isForMainFrame) return null

        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val documentHost = pageUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
        if (isAllowlisted(documentHost)) return null

        val requestHost = url.host?.lowercase()
        val type = ResourceType.infer(request)
        val isThirdParty = DomainUtils.isThirdParty(requestHost, documentHost)

        val match = runCatching {
            current.matchBlockingRule(url.toString(), requestHost, documentHost, type, isThirdParty)
        }.getOrNull() ?: return null

        blockedCount.incrementAndGet()
        return blockedResponse(request, match.redirectResource)
    }

    /**
     * The stand-in served for a blocked request.
     *
     * The content type matters as much as the emptiness. A page that asked for a script and
     * receives `text/plain` does not get a script that does nothing — it gets a resource the
     * browser refuses to execute, and any code waiting on that script waits until it times
     * out. YouTube's player does exactly this with its ad-status check, which showed up as a
     * five to ten second stall before playback. So each blocked request is answered with a
     * valid, empty thing of the kind it asked for, which is what uBlock Origin's `noop`
     * redirect resources do.
     *
     * When the matched rule named a specific `$redirect=` resource we recognise, that exact
     * stub is served instead of the generic empty one -- see [RedirectResources] for why that
     * sometimes matters.
     */
    private fun blockedResponse(request: WebResourceRequest, redirectResource: String?): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store"
        )

        if (redirectResource != null) {
            RedirectResources.response(redirectResource, headers)?.let { return it }
        }

        fun response(mimeType: String, encoding: String?, body: ByteArray) =
            WebResourceResponse(mimeType, encoding, 200, "OK", headers, ByteArrayInputStream(body))

        return when (ResourceType.infer(request)) {
            ResourceType.IMAGE -> response("image/gif", null, transparentGifBytes)
            ResourceType.SCRIPT -> response("application/javascript", "utf-8", EMPTY)
            ResourceType.STYLESHEET -> response("text/css", "utf-8", EMPTY)
            ResourceType.SUBDOCUMENT, ResourceType.DOCUMENT -> response("text/html", "utf-8", EMPTY)
            // Callers parse this; an empty body would throw where "{}" quietly yields nothing.
            ResourceType.XHR -> response("application/json", "utf-8", EMPTY_JSON)
            ResourceType.MEDIA -> response("video/mp4", null, EMPTY)
            ResourceType.FONT -> response("font/woff2", null, EMPTY)
            else -> response("text/plain", "utf-8", EMPTY)
        }
    }

    /** CSS injected at document start, or an empty string when nothing applies. */
    fun cssForHost(host: String?): String {
        if (!enabled) return ""
        if (isAllowlisted(host)) return ""
        return engine?.cssForHost(host).orEmpty()
    }

    /** Whether the YouTube-specific ad-stripping scripts should run for [host]. */
    fun isYouTubeBlockingEnabled(host: String?): Boolean {
        if (!enabled || !youTubeEnabled) return false
        return !isAllowlisted(host)
    }

    /**
     * Whether non-ad cosmetic site tweaks (hiding a redundant footer nav, a Shorts shelf, ...)
     * should run. Independent of ad blocking: a user may want one without the other, and unlike
     * ad blocking there is no per-host allowlist for it, since it isn't blocking anything.
     */
    fun isCustomSiteTweaksEnabled(): Boolean = false

    /** Whether a page's own fixed bottom bar should be hidden, per the in-drawer toggle. */
    fun isHideSiteFootersEnabled(): Boolean = false

    /** CSS for generic rules whose class or id turned up in the page's DOM. */
    fun cssForTokens(host: String?, classNames: String, ids: String): String {
        if (!enabled) return ""
        if (isAllowlisted(host)) return ""
        return engine?.cssForTokens(host, classNames, ids).orEmpty()
    }

    /**
     * The `:has-text()` rules for [host], as a JSON array of `[selector, regexSource]` pairs, for
     * the page's own JavaScript to apply -- there is no CSS equivalent for a text match, unlike
     * every other cosmetic rule this engine serves.
     */
    fun hasTextRulesForHost(host: String?): String {
        if (!enabled) return "[]"
        if (isAllowlisted(host)) return "[]"
        val rules = engine?.hasTextRulesForHost(host) ?: return "[]"
        if (rules.isEmpty()) return "[]"

        val array = org.json.JSONArray()
        for ((selector, pattern) in rules) {
            array.put(org.json.JSONArray().put(selector).put(pattern))
        }
        return array.toString()
    }
}
