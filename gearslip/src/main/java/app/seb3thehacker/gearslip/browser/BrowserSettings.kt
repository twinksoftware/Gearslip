package app.seb3thehacker.gearslip.browser

import android.content.Context
import android.content.SharedPreferences
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A page saved to the browser's start page. */
data class Bookmark(val title: String, val url: String) {
    /** The host without "www.", for labels and icons. */
    val host: String get() = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("").removePrefix("www.")
}

/** Where typed searches go. [query] is the address with the search terms appended, encoded. */
enum class SearchEngine(val id: String, val label: String, private val query: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
    STARTPAGE("startpage", "Startpage", "https://www.startpage.com/do/search?q="),
    BRAVE("brave", "Brave Search", "https://search.brave.com/search?q="),
    ECOSIA("ecosia", "Ecosia", "https://www.ecosia.org/search?q="),
    GOOGLE("google", "Google", "https://www.google.com/search?q="),
    BING("bing", "Bing", "https://www.bing.com/search?q=");

    fun search(text: String): String = query + URLEncoder.encode(text, "UTF-8")
}

/**
 * A filter list the ad blocker can use: one of the two it ships with, or one the driver added by
 * its address. [id] names the cached copy on disk.
 */
data class FilterList(val id: String, val title: String, val url: String, val builtIn: Boolean, val enabled: Boolean)

/**
 * The browser's own settings: bookmarks, the search engine, and the ad blocker's switches and
 * lists. Kept in a preferences file of their own and mirrored into flows, so the car's browser
 * and the phone's settings show the same thing. The ad blocker reads only the flows' values,
 * never the file, since it answers on WebView's request thread (see [adblock.AdBlockManager]).
 */
object BrowserSettings {

    private const val FILE = "browser"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_SEARCH = "search_engine"
    private const val KEY_ADBLOCK = "adblock"
    private const val KEY_YOUTUBE = "adblock_youtube"
    private const val KEY_LISTS_OFF = "adblock_lists_off"
    private const val KEY_CUSTOM_LISTS = "adblock_custom_lists"
    private const val KEY_LAST_CHECK = "adblock_last_check"

    /** The lists Gearslip ships a snapshot of, on unless turned off. */
    val BUILT_IN_LISTS = listOf(
        FilterList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", builtIn = true, enabled = true),
        FilterList("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", builtIn = true, enabled = true),
    )

    /** What a new install's start page offers, until the driver changes it. */
    private val DEFAULT_BOOKMARKS = listOf(
        Bookmark("YouTube", "https://m.youtube.com"),
        Bookmark("Wikipedia", "https://en.m.wikipedia.org"),
    )

    private val bookmarksFlow = MutableStateFlow<List<Bookmark>>(emptyList())
    private val searchFlow = MutableStateFlow(SearchEngine.DUCKDUCKGO)
    private val adBlockFlow = MutableStateFlow(true)
    private val youTubeFlow = MutableStateFlow(true)
    private val listsFlow = MutableStateFlow(BUILT_IN_LISTS)

    val bookmarks: StateFlow<List<Bookmark>> = bookmarksFlow
    val searchEngine: StateFlow<SearchEngine> = searchFlow
    val adBlock: StateFlow<Boolean> = adBlockFlow
    val youTubeAdBlock: StateFlow<Boolean> = youTubeFlow
    val filterLists: StateFlow<List<FilterList>> = listsFlow

    @Volatile private var prefs: SharedPreferences? = null

    /** Loads everything once per process. Safe to call from anywhere that has a context. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            bookmarksFlow.value = if (p.contains(KEY_BOOKMARKS)) readBookmarks(p.getString(KEY_BOOKMARKS, "[]").orEmpty()) else DEFAULT_BOOKMARKS
            searchFlow.value = SearchEngine.entries.firstOrNull { it.id == p.getString(KEY_SEARCH, null) } ?: SearchEngine.DUCKDUCKGO
            adBlockFlow.value = p.getBoolean(KEY_ADBLOCK, true)
            youTubeFlow.value = p.getBoolean(KEY_YOUTUBE, true)
            listsFlow.value = readLists(p)
            prefs = p
        }
    }

    // Bookmarks

    fun isBookmarked(url: String?): Boolean = url != null && bookmarksFlow.value.any { same(it.url, url) }

    /** Adds [url], or renames it when it's already saved. Returns false for something that isn't a web address. */
    fun addBookmark(title: String, url: String): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false
        val name = title.trim().ifEmpty { Bookmark("", clean).host.ifEmpty { clean } }
        val list = bookmarksFlow.value
        val existing = list.indexOfFirst { same(it.url, clean) }
        saveBookmarks(if (existing >= 0) list.toMutableList().also { it[existing] = Bookmark(name, list[existing].url) } else list + Bookmark(name, clean))
        return true
    }

    fun removeBookmark(url: String) = saveBookmarks(bookmarksFlow.value.filterNot { same(it.url, url) })

    /** Moves the bookmark at [from] to [to], for reordering from the phone. */
    fun moveBookmark(from: Int, to: Int) {
        val list = bookmarksFlow.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        saveBookmarks(list)
    }

    /** The same page whatever the trailing slash or scheme. */
    private fun same(a: String, b: String): Boolean {
        fun key(u: String) = u.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        return key(a).equals(key(b), ignoreCase = true)
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        bookmarksFlow.value = list
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs?.edit()?.putString(KEY_BOOKMARKS, array.toString())?.apply()
    }

    private fun readBookmarks(json: String): List<Bookmark> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { array.getJSONObject(it) }.map { Bookmark(it.optString("title"), it.optString("url")) }
            .filter { it.url.isNotEmpty() }
    }.getOrDefault(emptyList())

    // Search

    fun setSearchEngine(engine: SearchEngine) {
        searchFlow.value = engine
        prefs?.edit()?.putString(KEY_SEARCH, engine.id)?.apply()
    }

    /** Something that looks like an address opens as one; anything else is searched for. */
    fun addressToUrl(typed: String): String {
        val text = typed.trim()
        return when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            ' ' !in text && '.' in text -> "https://$text"
            else -> searchFlow.value.search(text)
        }
    }

    // Ad blocking

    fun setAdBlock(on: Boolean) {
        adBlockFlow.value = on
        prefs?.edit()?.putBoolean(KEY_ADBLOCK, on)?.apply()
    }

    fun setYouTubeAdBlock(on: Boolean) {
        youTubeFlow.value = on
        prefs?.edit()?.putBoolean(KEY_YOUTUBE, on)?.apply()
    }

    fun setListEnabled(id: String, on: Boolean) {
        val p = prefs ?: return
        val off = p.getStringSet(KEY_LISTS_OFF, emptySet()).orEmpty().toMutableSet()
        if (on) off.remove(id) else off.add(id)
        p.edit().putStringSet(KEY_LISTS_OFF, off).apply()
        listsFlow.value = readLists(p)
    }

    /** Adds a list by its address. Returns false for something that isn't one, or one already there. */
    fun addCustomList(url: String): Boolean {
        val p = prefs ?: return false
        val clean = url.trim()
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) return false
        if (listsFlow.value.any { it.url.equals(clean, ignoreCase = true) }) return false
        p.edit().putString(KEY_CUSTOM_LISTS, (customUrls(p) + clean).joinToString("\n")).apply()
        listsFlow.value = readLists(p)
        return true
    }

    fun removeCustomList(id: String) {
        val p = prefs ?: return
        val list = listsFlow.value.firstOrNull { it.id == id && !it.builtIn } ?: return
        p.edit().putString(KEY_CUSTOM_LISTS, customUrls(p).filterNot { it == list.url }.joinToString("\n")).apply()
        listsFlow.value = readLists(p)
    }

    var lastUpdateCheck: Long
        get() = prefs?.getLong(KEY_LAST_CHECK, 0L) ?: 0L
        set(value) { prefs?.edit()?.putLong(KEY_LAST_CHECK, value)?.apply() }

    private fun customUrls(p: SharedPreferences) = p.getString(KEY_CUSTOM_LISTS, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun readLists(p: SharedPreferences): List<FilterList> {
        val off = p.getStringSet(KEY_LISTS_OFF, emptySet()).orEmpty()
        val custom = customUrls(p).map { url ->
            val name = runCatching { android.net.Uri.parse(url).lastPathSegment }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
            FilterList(customId(url), name, url, builtIn = false, enabled = true)
        }
        return (BUILT_IN_LISTS + custom).map { it.copy(enabled = it.id !in off) }
    }

    /** A stable file name for a list known only by its address. */
    private fun customId(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return "custom-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
