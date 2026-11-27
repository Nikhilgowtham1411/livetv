package com.nikhil.livetv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

data class Lang(val label: String, val url: String)

private const val BASE = "https://iptv-org.github.io/iptv"

val LANGS = listOf(
    Lang("Telugu", "$BASE/languages/tel.m3u"),
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
            val list = parseM3u(body)
            if (list.isEmpty()) error("No channels found")
            list
        }
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
