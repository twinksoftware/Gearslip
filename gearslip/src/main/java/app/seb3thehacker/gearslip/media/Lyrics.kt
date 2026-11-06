package app.seb3thehacker.gearslip.media

import android.content.Context
import android.util.Base64
import android.util.Xml
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * One line. [words] is filled only for word-timed lyrics, and joined back together it reads as
 * [text]; each word carries the space after it, if any, so a word split into syllables stays whole.
 */
class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())

/** A word, or a syllable of one, sung from [startMs] to [endMs]. */
class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * [synced] means every line carries a time, so the current one can be followed along. [source]
 * names the service that had them, shown on debug builds.
 */
class LyricsResult(val lines: List<LyricLine>, val synced: Boolean, val source: String = "")

/**
 * Looks lyrics up online, in the order Metrolist uses: BetterLyrics, then LRCLIB, then KuGou.
 * Metrolist's other sources match songs by YouTube video id, which a song from another app
 * doesn't have, and Paxsenix calls Apple Music with a borrowed token, so they're left out.
 *
 * - BetterLyrics (lyrics-api.boidu.dev): word-timed lyrics, but only for songs its server has
 *   already fetched; anything else needs an API key and falls through.
 * - LRCLIB (lrclib.net): a free community database that needs no key.
 * - KuGou (kugou.com): a large Chinese music service, strong on Asian music.
 *
 * Media apps almost never publish lyrics in their session metadata, so these are the only general
 * source. Each lookup sends the track's title, artist, album and length to these services, which
 * is why it happens only when the driver opens the lyrics view, never in the background.
 *
 * Results are cached twice over: in memory for the life of the process, and on disk under the
 * cache directory so a song looked up on a past drive doesn't need the network again on this one.
 * A miss (no lyrics found) is cached in memory only - not on disk - so a transient lookup failure
 * doesn't harden into a permanent "no lyrics" the next time the same track plays.
 */
object Lyrics {

    private val cache = HashMap<String, LyricsResult?>()

    /** Longest any one source may take, DNS included: a server that never answers can't hold the rest up. */
    private const val SOURCE_TIMEOUT_MS = 12_000L
    /** How long a cached song waits before its sources are asked again for better lyrics. */
    private const val RECHECK_MS = 24 * 60 * 60 * 1000L
    private val io = CoroutineScope(Dispatchers.IO)

    /** Word-timed beats line-timed, which beats plain text. */
    private const val BEST = 3
    private fun quality(result: LyricsResult?): Int = when {
        result == null -> 0
        !result.synced -> 1
        result.lines.any { it.words.isNotEmpty() } -> BEST
        else -> 2
    }

    /** One place to look, with the best [quality] it can give. */
    private class Source(val name: String, val best: Int, val lookup: () -> LyricsResult?)

    private fun sources(title: String, artist: String, album: String, durationMs: Long): List<Source> {
        val seconds = (durationMs / 1000).toInt()
        return listOf(
            Source("BetterLyrics", BEST) { betterLyrics(title, artist, album, seconds) },
            Source("LRCLIB", 2) { exact(title, artist, album, durationMs) ?: search(title, artist) },
            Source("KuGou", 2) { kuGou(title, artist, album, seconds) },
        )
    }

    private fun key(title: String, artist: String, album: String, durationMs: Long) = "$title|$artist|$album|$durationMs"

    suspend fun find(context: Context, title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        if (title.isBlank()) return null
        val key = key(title, artist, album, durationMs)
        val memoryKey = key
        synchronized(cache) { if (cache.containsKey(memoryKey)) return cache[memoryKey] }

        val fromDisk = withContext(Dispatchers.IO) { readDisk(context, key) }
        if (fromDisk != null) {
            synchronized(cache) { cache[memoryKey] = fromDisk }
            return fromDisk
        }

        val result = firstBetter(sources(title, artist, album, durationMs), than = 0)
        synchronized(cache) { cache[memoryKey] = result }
        if (result != null) withContext(Dispatchers.IO) { writeDisk(context, key, result, System.currentTimeMillis()) }
        return result
    }

    /**
     * After [find] answered with [current], looks for better lyrics than it: word-timed where it has
     * line-timed, timed where it has plain. Only the sources that could beat it are asked, and only
     * once a day per song, so an old cache entry improves when a source gains the song without every
     * play sending the song out again. Returns the better lyrics, or null to keep [current].
     */
    suspend fun upgrade(
        context: Context, title: String, artist: String, album: String, durationMs: Long, current: LyricsResult,
    ): LyricsResult? {
        val have = quality(current)
        if (have >= BEST || title.isBlank()) return null
        val key = key(title, artist, album, durationMs)
        val checked = withContext(Dispatchers.IO) { checkedAt(context, key) }
        if (System.currentTimeMillis() - checked < RECHECK_MS) return null

        val candidates = sources(title, artist, album, durationMs).filter { it.best > have && it.name != current.source }
        val better = firstBetter(candidates, than = have)
        withContext(Dispatchers.IO) { writeDisk(context, key, better ?: current, System.currentTimeMillis()) }
        if (better == null) return null
        GearslipLog.i("lyrics: replaced ${current.source.ifEmpty { "cached" }} lyrics with ${better.source}'s")
        synchronized(cache) { cache[key] = better }
        return better
    }

    /** Asks [sources] in order and returns the first answer of a higher [quality] than [than]. */
    private suspend fun firstBetter(sources: List<Source>, than: Int): LyricsResult? {
        for (source in sources) {
            val job = io.async {
                runCatching(source.lookup).onFailure { GearslipLog.w("lyrics: ${source.name} failed: ${it.message}") }.getOrNull()
            }
            val result = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { job.await() }
                ?.let { LyricsResult(it.lines, it.synced, source.name) }
            if (quality(result) > than) {
                GearslipLog.i("lyrics: found on ${source.name} (${if (result!!.synced) "synced" else "plain"})")
                return result
            }
        }
        return null
    }

    private fun cacheFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, LYRICS_DIR).apply { mkdirs() }.resolve("$name.json")
    }

    /** Where the lyrics cache lives under the app's cache directory, for the storage screen too. */
    const val LYRICS_DIR = "lyrics"

    private fun readDisk(context: Context, key: String): LyricsResult? = runCatching {
        val file = cacheFile(context, key)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val lines = json.getJSONArray("lines").let { arr ->
            (0 until arr.length()).map { i ->
                val line = arr.getJSONObject(i)
                val words = line.optJSONArray("w")?.let { w ->
                    (0 until w.length()).map { j ->
                        val word = w.getJSONArray(j)
                        LyricWord(word.getLong(0), word.getLong(1), word.getString(2))
                    }
                }.orEmpty()
                LyricLine(line.getLong("t"), line.getString("x"), words)
            }
        }
        val source = json.optString("source")
        // Saved before word timings were kept: fetch again rather than wait a day for an upgrade
        // that skips the source it came from.
        if (source == "BetterLyrics" && lines.none { it.words.isNotEmpty() }) return null
        LyricsResult(lines, json.getBoolean("synced"), source)
    }.getOrNull()

    /** When the song's sources were last asked; 0 for entries from before the date was kept. */
    private fun checkedAt(context: Context, key: String): Long =
        runCatching { JSONObject(cacheFile(context, key).readText()).optLong("checked") }.getOrDefault(0L)

    private fun writeDisk(context: Context, key: String, result: LyricsResult, checked: Long) {
        runCatching {
            val lines = JSONArray()
            result.lines.forEach { line ->
                val entry = JSONObject().put("t", line.timeMs).put("x", line.text)
                if (line.words.isNotEmpty()) {
                    entry.put("w", JSONArray(line.words.map { JSONArray().put(it.startMs).put(it.endMs).put(it.text) }))
                }
                lines.put(entry)
            }
            val json = JSONObject().put("synced", result.synced).put("source", result.source)
                .put("checked", checked).put("lines", lines)
            cacheFile(context, key).writeText(json.toString())
        }.onFailure { GearslipLog.w("lyrics: could not write disk cache: ${it.message}") }
    }

    /** Forgets every cached song, on disk and in memory. */
    fun clearCache(context: Context) {
        synchronized(cache) { cache.clear() }
        File(context.cacheDir, LYRICS_DIR).deleteRecursively()
    }

    // --- BetterLyrics ---

    private fun betterLyrics(title: String, artist: String, album: String, seconds: Int): LyricsResult? {
        if (artist.isBlank()) return null
        val query = buildString {
            append("s=").append(enc(title)).append("&a=").append(enc(artist))
            if (seconds > 0) append("&d=").append(seconds)
            if (album.isNotBlank()) append("&al=").append(enc(album))
        }
        val body = get("https://lyrics-api.boidu.dev/getLyrics?$query") ?: return null
        val ttml = JSONObject(body).optString("ttml").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return parseTtml(ttml)
    }

    /**
     * Apple-style TTML: one `<p begin=…>` per line, its words in `<span begin=… end=…>`s with
     * spaces between; a word sung in syllables is several spans with no space. Background vocals,
     * translations and romanisations are left out, so a line reads as sung.
     */
    private fun parseTtml(ttml: String): LyricsResult? {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(ttml))
        }
        val lines = mutableListOf<LyricLine>()
        var inP = false
        var lineBegin: Long? = null
        var firstSpanBegin: Long? = null
        var skipDepth = 0
        val text = StringBuilder()
        // The span being read, and the words of the line so far, each with the space after it.
        var spanBegin: Long? = null
        var spanEnd: Long? = null
        val words = mutableListOf<Triple<Long, Long, StringBuilder>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when {
                    parser.name == "p" -> {
                        inP = true; text.clear(); firstSpanBegin = null; words.clear()
                        lineBegin = attr(parser, "begin")?.let(::ttmlMs)
                    }
                    inP && skipDepth > 0 -> skipDepth++
                    inP && parser.name == "span" -> {
                        val role = attr(parser, "role")
                        if (role == "x-bg" || role == "x-translation" || role == "x-roman") skipDepth = 1
                        else {
                            spanBegin = attr(parser, "begin")?.let(::ttmlMs)
                            spanEnd = attr(parser, "end")?.let(::ttmlMs)
                            if (firstSpanBegin == null) firstSpanBegin = spanBegin
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inP && skipDepth == 0) {
                    val raw = parser.text
                    text.append(raw)
                    val begin = spanBegin
                    val end = spanEnd
                    if (begin != null && end != null && raw.isNotBlank()) {
                        val word = raw.replace(Regex("\\s+"), " ").trimStart()
                        words.add(Triple(begin, end, StringBuilder(word)))
                    } else if (raw.isNotEmpty() && raw.isBlank() && words.isNotEmpty() && !words.last().third.endsWith(" ")) {
                        words.last().third.append(' ')
                    }
                }
                XmlPullParser.END_TAG -> when {
                    inP && skipDepth > 0 -> skipDepth--
                    inP && parser.name == "span" -> { spanBegin = null; spanEnd = null }
                    parser.name == "p" -> {
                        inP = false
                        val timed = words.map { (b, e, w) -> LyricWord(b, e, w.toString()) }
                        val line = if (timed.isNotEmpty()) timed.joinToString("") { it.text }.trim()
                        else text.toString().replace(Regex("\\s+"), " ").trim()
                        if (line.isNotEmpty()) lines.add(LyricLine(lineBegin ?: firstSpanBegin ?: -1, line, timed))
                    }
                }
            }
            event = parser.next()
        }
        if (lines.isEmpty()) return null
        val synced = lines.all { it.timeMs >= 0 } && lines.any { it.timeMs > 0 }
        return if (synced) LyricsResult(lines.sortedBy { it.timeMs }, synced = true)
        else LyricsResult(lines.map { LyricLine(0, it.text) }, synced = false)
    }

    private fun attr(parser: XmlPullParser, localName: String): String? =
        (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == localName }
            ?.let { parser.getAttributeValue(it) }

    /** TTML times: `1:23.456`, `1:02:03.4`, `83.456s`, `250ms` or a bare number of seconds. */
    private fun ttmlMs(raw: String): Long? {
        val t = raw.trim()
        val seconds = when {
            ':' in t -> t.split(':').fold(0.0) { acc, part -> acc * 60 + (part.toDoubleOrNull() ?: return null) }
            t.endsWith("ms") -> (t.dropLast(2).toDoubleOrNull() ?: return null) / 1000
            t.endsWith("s") -> t.dropLast(1).toDoubleOrNull() ?: return null
            t.endsWith("m") -> (t.dropLast(1).toDoubleOrNull() ?: return null) * 60
            t.endsWith("h") -> (t.dropLast(1).toDoubleOrNull() ?: return null) * 3600
            else -> t.toDoubleOrNull() ?: return null
        }
        return (seconds * 1000).toLong()
    }

    // --- LRCLIB ---

    private fun exact(title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        if (artist.isBlank()) return null
        val query = buildString {
            append("track_name=").append(enc(title)).append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationMs > 0) append("&duration=").append(durationMs / 1000)
        }
        return get("https://lrclib.net/api/get?$query")?.let { parse(JSONObject(it)) }
    }

    private fun search(title: String, artist: String): LyricsResult? {
        val body = get("https://lrclib.net/api/search?q=" + enc("$artist $title".trim())) ?: return null
        val hits = JSONArray(body)
        for (i in 0 until hits.length()) parse(hits.getJSONObject(i))?.let { return it }
        return null
    }

    private fun parse(json: JSONObject): LyricsResult? {
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return LyricsResult(lines, synced = true)
        }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return LyricsResult(plain.lines().map { LyricLine(0, it) }, synced = false)
    }

    // --- KuGou ---

    /** Songs within this many seconds of the playing one count as the same recording. */
    private const val KUGOU_DURATION_SLACK = 8
    private val KUGOU_TIMED = Regex("""\[(\d\d):(\d\d)\.(\d{2,3})].*""")
    /** A credit line such as `[00:01.00]作词：…` or `[00:02.00]Composer: …`. */
    private val KUGOU_CREDIT = Regex(""".+].+[:：].+""")
    private const val KUGOU_CREDIT_LINES = 30

    private fun kuGou(title: String, artist: String, album: String, seconds: Int): LyricsResult? {
        val keyword = buildString {
            append(kuGouTitle(title)).append(" - ").append(kuGouArtist(artist))
            if (album.isNotBlank()) append(' ').append(album)
        }
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        // First the song itself, matched by length, then its lyrics by the song's hash.
        val songs = get(
            "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$encoded",
        )?.let { JSONObject(it).optJSONObject("data")?.optJSONArray("info") }
        if (songs != null) for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            if (seconds > 0 && abs(song.optInt("duration") - seconds) > KUGOU_DURATION_SLACK) continue
            val hash = song.optString("hash").takeIf { it.isNotBlank() } ?: continue
            kuGouCandidate("hash=" + enc(hash))?.let { return it }
        }
        // Then a plain lyrics search by name.
        val byName = "keyword=$encoded" + if (seconds > 0) "&duration=${seconds * 1000}" else ""
        return kuGouCandidate(byName)
    }

    private fun kuGouCandidate(query: String): LyricsResult? {
        val body = get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&$query") ?: return null
        val candidate = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val id = candidate.optLong("id").takeIf { it != 0L } ?: return null
        val accessKey = candidate.optString("accesskey").takeIf { it.isNotBlank() } ?: return null
        val download = get(
            "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=$id&accesskey=${enc(accessKey)}",
        ) ?: return null
        val content = JSONObject(download).optString("content").takeIf { it.isNotBlank() } ?: return null
        val lrc = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        val lines = parseLrc(kuGouTrim(lrc).joinToString("\n")).filter { it.text.isNotEmpty() }
        return if (lines.isEmpty()) null else LyricsResult(lines, synced = true)
    }

    /** Keeps the timed lines and drops the songwriter and producer credits KuGou puts at either end. */
    private fun kuGouTrim(lrc: String): List<String> {
        val timed = lrc.lines().filter { it.matches(KUGOU_TIMED) }
        if (timed.isEmpty()) return timed
        val headLimit = minOf(KUGOU_CREDIT_LINES, timed.lastIndex)
        val head = (headLimit downTo 0).firstOrNull { timed[it].matches(KUGOU_CREDIT) }?.plus(1) ?: 0
        val rest = timed.drop(head)
        if (rest.isEmpty()) return rest
        val tailLimit = minOf(KUGOU_CREDIT_LINES, rest.lastIndex)
        val tail = (tailLimit downTo 0).firstOrNull { rest[rest.lastIndex - it].matches(KUGOU_CREDIT) }?.plus(1) ?: 0
        return rest.dropLast(tail)
    }

    /** KuGou's titles leave out bracketed extras such as "(feat. …)" or "（Live）". */
    private fun kuGouTitle(title: String) = listOf("\\(.*\\)", "（.*）", "「.*」", "『.*』", "<.*>", "《.*》", "〈.*〉", "＜.*＞")
        .fold(title) { acc, pattern -> acc.replace(Regex(pattern), "") }.trim()

    /** KuGou joins several artists with "、". */
    private fun kuGouArtist(artist: String) = artist.replace(", ", "、").replace(" & ", "、").replace(".", "")
        .replace(Regex("\\(.*\\)"), "").replace(Regex("（.*）"), "").trim()

    // --- shared ---

    /** `[01:23.45] words` - a line may carry several stamps when a chorus repeats. */
    private fun parseLrc(text: String): List<LyricLine> {
        val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        return text.lines().flatMap { raw ->
            val stamps = stamp.findAll(raw).toList()
            val words = raw.replace(stamp, "").trim()
            stamps.map {
                val ms = ((it.groupValues[1].toLong() * 60 + it.groupValues[2].toDouble()) * 1000).toLong()
                LyricLine(ms, words)
            }
        }.sortedBy { it.timeMs }
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Gearslip (car media host)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
