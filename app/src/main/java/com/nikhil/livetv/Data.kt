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

data class Lang(val label: String, val url: String, val keywords: List<String>? = null)

private const val BASE = "https://iptv-org.github.io/iptv"

private const val UA =
    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

val LANGS = listOf(
    Lang("Telugu", "$BASE/languages/tel.m3u"),
    Lang(
        "Popular", "$BASE/countries/in.m3u",
        listOf(
            "maa", "zee", "star", "sony", "colors", "history", "tlc", "disney", "pogo", "nick",
            "cartoon", "hungama", "sun ", "gemini", "etv", "discovery", "animal planet", "nat geo",
            "&tv", "mtv", "aaj tak", "ndtv", "tv9", "ntv", "sakshi",
        ),
    ),
    Lang("Kids", "$BASE/categories/kids.m3u"),
    Lang("Documentary", "$BASE/categories/documentary.m3u"),
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

    suspend fun load(ctx: Context, lang: Lang): Result<List<Channel>> = withContext(Dispatchers.IO) {
        val cache = File(ctx.cacheDir, "pl_" + lang.label.hashCode() + ".m3u")
        runCatching {
            val body = try {
                val req = Request.Builder().url(lang.url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP " + resp.code)
                    resp.body!!.string()
                }.also { cache.writeText(it) }
            } catch (e: Exception) {
                if (cache.exists()) cache.readText() else throw e
            }
            val all = parseM3u(body)
            val kw = lang.keywords
            val list = if (kw == null) all else all.filter { c ->
                val n = c.name.lowercase()
                kw.any { n.contains(it) }
            }
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
        val f = File(ctx.cacheDir, "alive_" + lang.label.hashCode() + ".txt")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 45 * 60 * 1000L
        var alive: List<Channel> = emptyList()
        if (fresh) {
            val set = withContext(Dispatchers.IO) { f.readLines().toHashSet() }
            alive = all.filter { it.url in set }
        }
        if (alive.isEmpty()) {
            alive = filterAlive(all, onProgress)
            if (alive.isNotEmpty()) {
                withContext(Dispatchers.IO) { f.writeText(alive.joinToString("\n") { it.url }) }
            }
        }
        return if (alive.isEmpty()) Result.failure(Exception("No working channels right now")) else Result.success(alive)
    }
}

class FavStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("favs", Context.MODE_PRIVATE)

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
