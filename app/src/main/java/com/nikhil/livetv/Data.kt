package com.nikhil.livetv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val userAgent: String?,
    val referrer: String?,
)

data class Lang(
    val label: String,
    val url: String,
    val keywords: List<String>? = null,
    val extraUrl: String? = null,
    val extraKeywords: List<String> = emptyList(),
    val customKey: String? = null,
    val priority: List<String> = emptyList(),
)

val TELUGU_PRIORITY = listOf(
    "star maa", "maa", "zee telugu", "zee", "gemini", "etv", "tv9", "ntv", "sakshi", "hmtv", "abn", "tv5",
    "10tv", "v6", "studio n", "mahaa", "bhakti",
)

val KIDS_KW = listOf(
    "cartoon network", "disney", "pogo", "nick", "hungama", "kushi", "sony yay", "discovery kids",
    "chutti", "toon", "baby tv", "boomerang",
)

val DOC_KW = listOf(
    "national geographic", "nat geo", "discovery", "history", "animal planet", "tlc", "sony bbc", "fyi",
)

val TELUGU_MUSIC_KW = listOf(
    "gemini music", "maa music", "star maa music", "zee music", "telugu music", "raj music", "9xm telugu",
)

val SPORTS_KW = listOf(
    "star sports", "sony sports", "sony ten", "sports18", "sports 18", "dd sports", "willow", "cricket",
    "ten sports", "espn", "eurosport", "sky sports", "bein", "fox sports", "t sports", "ptv sports",
)

val TELUGU_KW = listOf(
    "telugu", "maa", "etv", "gemini", "sakshi", "ntv", "hmtv", "abn", "tv5", "10tv", "v6",
    "studio n", "mahaa", "bhakti", "swarnamukhi", "zee cinemalu", "cvr", "tv9", "raj news",
)

private const val BASE = "https://iptv-org.github.io/iptv"

private const val UA =
    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

val LANGS = listOf(
    Lang(
        "Telugu", "$BASE/languages/tel.m3u",
        extraUrl = "$BASE/countries/in.m3u",
        extraKeywords = TELUGU_KW,
        customKey = "telugu_custom",
        priority = TELUGU_PRIORITY,
    ),
    Lang(
        "Popular", "$BASE/countries/in.m3u",
        listOf(
            "maa", "zee", "star", "sony", "colors", "history", "tlc", "disney", "pogo", "nick",
            "cartoon", "hungama", "sun ", "gemini", "etv", "discovery", "animal planet", "nat geo",
            "&tv", "mtv", "aaj tak", "ndtv", "tv9", "ntv", "sakshi",
        ),
        extraUrl = "$BASE/index.m3u",
        extraKeywords = KIDS_KW + DOC_KW,
        priority = KIDS_KW + DOC_KW,
    ),
    Lang(
        "Kids", "$BASE/categories/kids.m3u",
        extraUrl = "$BASE/countries/in.m3u",
        extraKeywords = KIDS_KW,
        priority = KIDS_KW,
    ),
    Lang(
        "Sports", "$BASE/categories/sports.m3u",
        extraUrl = "$BASE/countries/in.m3u",
        extraKeywords = SPORTS_KW,
        priority = SPORTS_KW,
    ),
    Lang(
        "Telugu Music", "$BASE/categories/music.m3u",
        extraUrl = "$BASE/countries/in.m3u",
        extraKeywords = TELUGU_MUSIC_KW,
        priority = TELUGU_MUSIC_KW,
    ),
    Lang(
        "Documentary", "$BASE/categories/documentary.m3u",
        extraUrl = "$BASE/countries/in.m3u",
        extraKeywords = DOC_KW,
        priority = DOC_KW,
    ),
    Lang("Entertainment", "$BASE/categories/entertainment.m3u"),
    Lang("Movies", "$BASE/categories/movies.m3u"),
    Lang("News", "$BASE/categories/news.m3u"),
    Lang("Music", "$BASE/categories/music.m3u"),
    Lang("Hindi", "$BASE/languages/hin.m3u"),
    Lang("Tamil", "$BASE/languages/tam.m3u"),
    Lang("Kannada", "$BASE/languages/kan.m3u"),
    Lang("Malayalam", "$BASE/languages/mal.m3u"),
    Lang("English", "$BASE/languages/eng.m3u"),
    Lang("Bengali", "$BASE/languages/ben.m3u"),
    Lang("Marathi", "$BASE/languages/mar.m3u"),
    Lang("Gujarati", "$BASE/languages/guj.m3u"),
    Lang("Punjabi", "$BASE/languages/pan.m3u"),
    Lang("Urdu", "$BASE/languages/urd.m3u"),
    Lang("All India", "$BASE/countries/in.m3u"),
)

/** Vertical resolution parsed from the channel name, e.g. "(1080p)" -> 1080, "4K" -> 2160, unknown -> 0. */
fun qualityOf(c: Channel): Int {
    val n = c.name.lowercase()
    if (n.contains("4k") || n.contains("2160") || n.contains("uhd")) return 2160
    return Regex("(\\d{3,4})p").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

private fun rank(c: Channel, priority: List<String>): Int {
    val n = c.name.lowercase()
    val i = priority.indexOfFirst { n.contains(it) }
    return if (i < 0) Int.MAX_VALUE else i
}

fun sortChannels(list: List<Channel>, priority: List<String>): List<Channel> =
    list.sortedWith(compareBy<Channel>({ rank(it, priority) }, { it.name.lowercase() }))

private fun attr(line: String, key: String): String? {
    val m = Regex(key + "=\"([^\"]*)\"").find(line)
    return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

fun parseM3u(text: String): List<Channel> {
    val out = ArrayList<Channel>()
    var name: String? = null
    var logo: String? = null
    var group: String? = null
    var ua: String? = null
    var ref: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        when {
            line.startsWith("#EXTINF") -> {
                name = line.substringAfterLast(",").trim()
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title")
                ua = null
                ref = null
            }
            line.startsWith("#EXTVLCOPT:http-referrer=") -> ref = line.substringAfter("=").trim()
            line.startsWith("#EXTVLCOPT:http-user-agent=") -> ua = line.substringAfter("=").trim()
            line.startsWith("#") -> {}
            else -> {
                val n = name
                if (n != null && (line.startsWith("http://") || line.startsWith("https://"))) {
                    out.add(Channel(n.ifEmpty { "Channel" }, line, logo, group, ua, ref))
                }
                name = null
            }
        }
    }
    return out.distinctBy { it.url }.sortedBy { it.name.lowercase() }
}

object Repo {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun fetch(ctx: Context, url: String): String {
        val cache = File(ctx.cacheDir, "pl_" + url.hashCode() + ".m3u")
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP " + resp.code)
                resp.body!!.string()
            }.also { cache.writeText(it) }
        } catch (e: Exception) {
            if (cache.exists()) cache.readText() else throw e
        }
    }

    private fun matches(c: Channel, kw: List<String>): Boolean {
        val n = c.name.lowercase()
        val g = (c.group ?: "").lowercase()
        return kw.any { n.contains(it) || g.contains(it) }
    }

    suspend fun load(ctx: Context, lang: Lang): Result<List<Channel>> = withContext(Dispatchers.IO) {
        runCatching {
            val all = parseM3u(fetch(ctx, lang.url))
            val kw = lang.keywords
            var list = if (kw == null) all else all.filter { matches(it, kw) }
            lang.extraUrl?.let { eu ->
                val extra = runCatching { parseM3u(fetch(ctx, eu)) }.getOrDefault(emptyList())
                list = list + extra.filter { matches(it, lang.extraKeywords) }
            }
            lang.customKey?.let { list = list + FavStore(ctx, it).load() }
            list = sortChannels(list.distinctBy { it.url }, lang.priority)
            if (list.isEmpty()) error("No channels found")
            list
        }
    }

    private val probeClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    private fun probe(ch: Channel): Boolean = try {
        val rb = Request.Builder().url(ch.url).header("User-Agent", ch.userAgent ?: UA)
        ch.referrer?.let { rb.header("Referer", it) }
        probeClient.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) {
                false
            } else {
                val ct = (r.header("Content-Type") ?: "").lowercase()
                val src = r.body?.source()
                if (src == null || !src.request(16)) {
                    false
                } else {
                    val head = src.peek().readUtf8(16)
                    val isPlaylist = ch.url.contains(".m3u8") || ct.contains("mpegurl")
                    when {
                        isPlaylist -> head.startsWith("#EXTM3U")
                        ct.contains("text/html") -> false
                        else -> true
                    }
                }
            }
        }
    } catch (e: Exception) {
        false
    }

    private suspend fun filterAlive(list: List<Channel>, onProgress: (Int, Int) -> Unit): List<Channel> {
        val alive = ConcurrentHashMap.newKeySet<String>()
        val done = AtomicInteger(0)
        val sem = Semaphore(32)
        withTimeoutOrNull(45_000) {
            coroutineScope {
                list.map { ch ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            if (probe(ch)) alive.add(ch.url)
                            onProgress(done.incrementAndGet(), list.size)
                        }
                    }
                }.joinAll()
            }
        }
        return list.filter { it.url in alive }
    }

    /** Loads a playlist and keeps only channels that respond right now (cached for 45 min). */
    suspend fun loadWorking(ctx: Context, lang: Lang, onProgress: (Int, Int) -> Unit): Result<List<Channel>> {
        val r = load(ctx, lang)
        val all = r.getOrNull() ?: return r
        val customUrls = lang.customKey?.let { k -> FavStore(ctx, k).load().map { it.url }.toSet() } ?: emptySet()
        val base = all.filter { it.url !in customUrls }
        val f =File(ctx.cacheDir, "alive_" + lang.label.hashCode() + ".txt")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 45 * 60 * 1000L
        var alive: List<Channel> = emptyList()
        if (fresh) {
            val set = withContext(Dispatchers.IO) { f.readLines().toHashSet() }
            alive = base.filter { it.url in set }
        }
        if (alive.isEmpty()) {
            alive = filterAlive(base, onProgress)
            if (alive.isNotEmpty()) {
                withContext(Dispatchers.IO) { f.writeText(alive.joinToString("\n") { it.url }) }
            }
        }
        val result = sortChannels(alive + all.filter { it.url in customUrls }, lang.priority)
        return if (result.isEmpty()) Result.failure(Exception("No working channels right now")) else Result.success(result)
    }
}

class FavStore(ctx: Context, name: String = "favs") {
    private val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun load(): List<Channel> = runCatching {
        val a = JSONArray(sp.getString("list", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Channel(
                o.getString("n"),
                o.getString("u"),
                o.optString("l").ifBlank { null },
                null,
                o.optString("ua").ifBlank { null },
                o.optString("r").ifBlank { null },
            )
        }
    }.getOrDefault(emptyList())

    fun isFav(c: Channel): Boolean = load().any { it.url == c.url }

    /** Returns true if the channel is now a favourite. */
    fun toggle(c: Channel): Boolean {
        val cur = load().toMutableList()
        val had = cur.removeAll { it.url == c.url }
        if (!had) cur.add(c)
        val a = JSONArray()
        cur.forEach {
            a.put(
                JSONObject()
                    .put("n", it.name)
                    .put("u", it.url)
                    .put("l", it.logo ?: "")
                    .put("ua", it.userAgent ?: "")
                    .put("r", it.referrer ?: "")
            )
        }
        sp.edit().putString("list", a.toString()).apply()
        return !had
    }
}
