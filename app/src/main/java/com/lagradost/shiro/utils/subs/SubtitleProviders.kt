package com.lagradost.shiro.utils.subs

import android.content.Context
import com.lagradost.shiro.utils.auth.AuthManager
import com.lagradost.shiro.utils.integrations.IntegrationPrefs
import com.lagradost.shiro.utils.mvvm.logError
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** One downloadable subtitle. [ref] is provider specific (file id / zip path / direct url). */
data class SubtitleResult(
    val provider: String,
    val name: String,
    val language: String,
    val ref: String,
    val headers: Map<String, String>? = null,
) {
    val label get() = "$name  [$provider${if (language.isNotEmpty()) " • $language" else ""}]"
}

/**
 * OpenSubtitles.com (REST v1), SubDL, plus subtitles that CloudStream extensions attach to
 * their links. All calls block; call from a worker thread.
 */
object SubtitleProviders {
    private const val OS_API = "https://api.opensubtitles.com/api/v1"
    private const val UA = "Nekomo v1.0"
    private const val SUBDL_API = "https://api.subdl.com/api/v1/subtitles"
    private const val SUBDL_DL = "https://dl.subdl.com"

    /** Subtitles handed over by extension providers for the stream currently being resolved. */
    val extensionSubtitles = CopyOnWriteArrayList<SubtitleResult>()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- search ----------

    /** Searches every configured provider in parallel and merges the results. */
    fun search(context: Context, title: String, episode: Int?, language: String): List<SubtitleResult> {
        val prefs = IntegrationPrefs(context)
        val out = CopyOnWriteArrayList<SubtitleResult>()
        val threads = ArrayList<Thread>()
        if (prefs.openSubsApiKey.isNotEmpty()) threads.add(thread { out.addAll(searchOpenSubtitles(prefs, title, episode, language)) })
        if (prefs.subDlApiKey.isNotEmpty()) threads.add(thread { out.addAll(searchSubDl(prefs, title, episode, language)) })
        threads.forEach { it.join(30_000) }
        return extensionSubtitles.toList() + out.toList()
    }

    fun searchOpenSubtitles(prefs: IntegrationPrefs, title: String, episode: Int?, language: String): List<SubtitleResult> = try {
        val url = buildString {
            append("$OS_API/subtitles?languages=${enc(language.lowercase())}&query=${enc(title)}")
            if (episode != null) append("&type=episode&episode_number=$episode")
        }
        val res = khttp.get(url, headers = osHeaders(prefs), timeout = 30.0)
        if (res.statusCode != 200) emptyList() else {
            AuthManager.mapper.readTree(res.text).path("data")
                .sortedByDescending { it.path("attributes").path("download_count").asInt(0) }
                .mapNotNull { d ->
                    val a = d.path("attributes")
                    val fileId = a.path("files").path(0).path("file_id").asText("")
                    if (fileId.isEmpty()) null else SubtitleResult(
                        "OpenSubtitles",
                        a.path("release").asText(a.path("files").path(0).path("file_name").asText("Subtitle")),
                        a.path("language").asText(language),
                        fileId
                    )
                }.take(15)
        }
    } catch (e: Exception) {
        logError(e); emptyList()
    }

    fun searchSubDl(prefs: IntegrationPrefs, title: String, episode: Int?, language: String): List<SubtitleResult> = try {
        val url = buildString {
            append("$SUBDL_API?api_key=${enc(prefs.subDlApiKey)}&film_name=${enc(title)}")
            append("&languages=${enc(language.uppercase())}&subs_per_page=20")
            if (episode != null) append("&type=tv&episode_number=$episode")
        }
        val res = khttp.get(url, timeout = 30.0)
        val root = AuthManager.mapper.readTree(res.text)
        if (res.statusCode != 200 || !root.path("status").asBoolean(false)) emptyList()
        else root.path("subtitles").mapNotNull { s ->
            val u = s.path("url").asText("")
            if (u.isEmpty()) null else SubtitleResult(
                "SubDL",
                s.path("release_name").asText(s.path("name").asText("Subtitle")),
                s.path("language").asText(language),
                u
            )
        }.take(15)
    } catch (e: Exception) {
        logError(e); emptyList()
    }

    // ---------- OpenSubtitles auth ----------

    private fun osHeaders(prefs: IntegrationPrefs, token: String? = null): Map<String, String> {
        val h = HashMap<String, String>()
        h["Api-Key"] = prefs.openSubsApiKey
        h["User-Agent"] = UA
        h["Accept"] = "application/json"
        if (token != null) h["Authorization"] = "Bearer $token"
        return h
    }

    @Volatile private var osToken: Pair<String, Long>? = null

    /** @return (token or null, message) */
    fun openSubtitlesLogin(prefs: IntegrationPrefs): Pair<String?, String> {
        osToken?.let { if (System.currentTimeMillis() < it.second) return it.first to "OK" }
        if (prefs.openSubsApiKey.isEmpty()) return null to "Enter your OpenSubtitles API key"
        if (prefs.openSubsUser.isEmpty() || prefs.openSubsPassword.isEmpty())
            return null to "Enter your OpenSubtitles username and password (needed for downloads)"
        return try {
            val res = khttp.post(
                "$OS_API/login",
                headers = osHeaders(prefs) + ("Content-Type" to "application/json"),
                json = AuthManager.mapper.writeValueAsString(
                    mapOf("username" to prefs.openSubsUser, "password" to prefs.openSubsPassword)
                ),
                timeout = 30.0
            )
            val token = AuthManager.mapper.readTree(res.text).path("token").asText("")
            if (res.statusCode == 200 && token.isNotEmpty()) {
                osToken = token to System.currentTimeMillis() + 20 * 3600 * 1000L
                token to "OK"
            } else null to (if (res.statusCode == 401) "Wrong OpenSubtitles username/password" else "OpenSubtitles login failed (${res.statusCode})")
        } catch (e: Exception) {
            null to "Network error: ${e.message}"
        }
    }

    // ---------- download ----------

    /** Downloads a subtitle into the cache and returns it as a UTF-8 file (or null on failure). */
    fun download(context: Context, r: SubtitleResult): File? = try {
        val dir = File(context.cacheDir, "subs").apply { mkdirs() }
        val key = MessageDigest.getInstance("MD5").digest("${r.provider}|${r.ref}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        dir.listFiles { f -> f.name.startsWith(key) }?.firstOrNull()?.takeIf { it.length() > 0 }
            ?: when (r.provider) {
                "OpenSubtitles" -> downloadOpenSubtitles(context, r, dir, key)
                "SubDL" -> downloadSubDl(r, dir, key)
                else -> downloadDirect(r, dir, key)
            }
    } catch (e: Exception) {
        logError(e); null
    }

    private fun downloadOpenSubtitles(context: Context, r: SubtitleResult, dir: File, key: String): File? {
        val prefs = IntegrationPrefs(context)
        val token = openSubtitlesLogin(prefs).first ?: return null
        val res = khttp.post(
            "$OS_API/download",
            headers = osHeaders(prefs, token) + ("Content-Type" to "application/json"),
            json = """{"file_id":${r.ref},"sub_format":"srt"}""",
            timeout = 30.0
        )
        if (res.statusCode == 401) osToken = null
        val link = AuthManager.mapper.readTree(res.text).path("link").asText("")
        if (res.statusCode != 200 || link.isEmpty()) return null
        val file = khttp.get(link, headers = mapOf("User-Agent" to UA), timeout = 30.0)
        return if (file.statusCode == 200) save(dir, key, "srt", file.content) else null
    }

    private fun downloadSubDl(r: SubtitleResult, dir: File, key: String): File? {
        val res = khttp.get(SUBDL_DL + r.ref, timeout = 45.0)
        if (res.statusCode != 200) return null
        return extractFromZip(res.content)?.let { (ext, bytes) -> save(dir, key, ext, bytes) }
    }

    private fun downloadDirect(r: SubtitleResult, dir: File, key: String): File? {
        val res = khttp.get(r.ref, headers = r.headers ?: emptyMap(), timeout = 30.0)
        if (res.statusCode != 200) return null
        val ext = r.ref.substringBefore("?").substringAfterLast('.', "srt").lowercase()
            .takeIf { it in supportedExt } ?: "srt"
        return save(dir, key, ext, res.content)
    }

    val supportedExt = setOf("srt", "vtt", "ass", "ssa")

    /** First supported subtitle file inside a zip. */
    fun extractFromZip(zip: ByteArray): Pair<String, ByteArray>? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (!entry.isDirectory && ext in supportedExt) return ext to zin.readBytes()
                entry = zin.nextEntry
            }
        }
        return null
    }

    private fun save(dir: File, key: String, ext: String, bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        val file = File(dir, "$key.$ext")
        file.writeBytes(toUtf8(bytes))
        return file
    }

    /** ExoPlayer reads subtitles as UTF-8; re-encode legacy Windows-1252/Latin-1 files. */
    fun toUtf8(bytes: ByteArray): ByteArray {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
            body
        } catch (e: CharacterCodingException) {
            String(body, charset("windows-1252")).toByteArray(Charsets.UTF_8)
        }
    }
}
