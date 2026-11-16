package khttp

import com.lagradost.shiro.utils.DohProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/*
 * Minimal drop-in replacement for the io.karn:khttp library (dead jcenter dep).
 * Implements the small API surface this app uses: get/post/put/delete/head/patch
 * with headers/data/json/params/timeout/cookies/stream/allowRedirects,
 * and a Response exposing text/content/url/statusCode/headers/cookies.
 *
 * Backed by OkHttp internally (instead of raw HttpURLConnection) so the "DNS over HTTPS"
 * setting (see utils/DohProvider.kt) transparently applies to every API call in the app.
 */

object structures {
    object cookie {
        class CookieJar : LinkedHashMap<String, String> {
            constructor() : super()
            constructor(map: Map<String, String>) : super(map)
        }
    }
}

private typealias CookieJar = khttp.structures.cookie.CookieJar

class Response(
    val statusCode: Int,
    val url: String,
    val headers: Map<String, String>,
    val cookies: CookieJar,
    val content: ByteArray,
    val connection: HttpURLConnection?,
) {
    val text: String
        get() = content.toString(Charsets.UTF_8)

    val jsonObject: org.json.JSONObject
        get() = org.json.JSONObject(text)
}

private fun buildQuery(params: Map<String, String>?): String {
    if (params.isNullOrEmpty()) return ""
    return params.entries.joinToString("&", prefix = "?") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
}

// The base client is cached and only rebuilt when the DNS-over-HTTPS setting actually
// changes, so switching it in Settings applies immediately without an app restart, while
// normal requests still benefit from OkHttp's connection pooling.
@Volatile
private var cachedClient: OkHttpClient? = null

@Volatile
private var cachedDnsKey: String? = null
private val clientLock = Any()

private fun baseClient(): OkHttpClient {
    val key = DohProvider.currentKey()
    cachedClient?.let { if (cachedDnsKey == key) return it }
    synchronized(clientLock) {
        cachedClient?.let { if (cachedDnsKey == key) return it }
        val built = OkHttpClient.Builder()
            .dns(DohProvider.buildDns())
            .build()
        cachedClient = built
        cachedDnsKey = key
        return built
    }
}

private fun request(
    method: String,
    url: String,
    params: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    data: Any? = null,
    json: Any? = null,
    timeout: Double = 30.0,
    allowRedirects: Boolean = true,
    stream: Boolean = false,
    cookies: Map<String, String>? = null,
): Response {
    val fullUrl = if (url.contains("?") || params == null) url else url + buildQuery(params)

    val requestBuilder = Request.Builder().url(fullUrl)
    requestBuilder.header("Accept-Encoding", "gzip")
    requestBuilder.header("User-Agent", "khttp/1.0.0")
    requestBuilder.header("Accept", "*/*")
    headers?.forEach { (k, v) -> requestBuilder.header(k, v) }
    if (!cookies.isNullOrEmpty()) {
        requestBuilder.header(
            "Cookie",
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    val body = when {
        json != null -> {
            requestBuilder.header("Content-Type", "application/json")
            (if (json is String) json else mapper.writeValueAsString(json))
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        }
        data is Map<*, *> -> {
            data.entries.joinToString("&") {
                "${URLEncoder.encode(it.key.toString(), "UTF-8")}=${
                    URLEncoder.encode(it.value.toString(), "UTF-8")
                }"
            }.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        }
        data is ByteArray -> data.toRequestBody(null)
        data != null -> data.toString().toRequestBody(null)
        else -> null
    }

    when (method) {
        "GET" -> requestBuilder.get()
        "HEAD" -> requestBuilder.head()
        // POST/PUT/PATCH require a non-null body in OkHttp even when the caller passed no
        // data/json (matches the old HttpURLConnection impl, which just sent no body at all).
        else -> requestBuilder.method(method, body ?: ByteArray(0).toRequestBody(null))
    }

    val timeoutMs = (timeout * 1000).toLong().coerceAtLeast(1000L)
    var client = baseClient().newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
    if (!allowRedirects) {
        client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    client.newCall(requestBuilder.build()).execute().use { response ->
        val responseCookies = CookieJar()
        response.headers.values("Set-Cookie").forEach { raw ->
            val first = raw.split(";")[0]
            val idx = first.indexOf('=')
            if (idx > 0) responseCookies[first.substring(0, idx)] = first.substring(idx + 1)
        }

        val rawContent = if (stream || method == "HEAD") {
            ByteArray(0)
        } else {
            try {
                response.body?.bytes() ?: ByteArray(0)
            } catch (e: Exception) {
                ByteArray(0)
            }
        }

        // We set Accept-Encoding manually above, which stops OkHttp from auto-decompressing,
        // so decode it ourselves - matches what the previous HttpURLConnection impl did.
        val content = if ("gzip".equals(response.header("Content-Encoding"), true)) {
            try {
                GZIPInputStream(rawContent.inputStream()).readBytes()
            } catch (e: Exception) {
                rawContent
            }
        } else rawContent

        val headerMap = mutableMapOf<String, String>()
        for (name in response.headers.names()) {
            headerMap[name] = response.headers.values(name).joinToString(", ")
        }

        return Response(
            response.code,
            response.request.url.toString(),
            headerMap,
            responseCookies,
            content,
            null,
        )
    }
}

private val mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()

fun get(
    url: String,
    params: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    data: Any? = null,
    json: Any? = null,
    timeout: Double = 30.0,
    allowRedirects: Boolean = true,
    stream: Boolean = false,
    cookies: Map<String, String>? = null,
) = request("GET", url, params, headers, data, json, timeout, allowRedirects, stream, cookies)

fun post(
    url: String,
    params: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    data: Any? = null,
    json: Any? = null,
    timeout: Double = 30.0,
    allowRedirects: Boolean = true,
    stream: Boolean = false,
    cookies: Map<String, String>? = null,
) = request("POST", url, params, headers, data, json, timeout, allowRedirects, stream, cookies)

fun put(
    url: String,
    params: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    data: Any? = null,
    json: Any? = null,
    timeout: Double = 30.0,
    allowRedirects: Boolean = true,
    stream: Boolean = false,
    cookies: Map<String, String>? = null,
) = request("PUT", url, params, headers, data, json, timeout, allowRedirects, stream, cookies)

fun delete(
    url: String,
    params: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    data: Any? = null,
    json: Any? = null,
    timeout: Double = 30.0,
    allowRedirects: Boolean = true,
    stream: Boolean = false,
    cookies: Map<String, String>? = null,
) = request("DELETE", url, params, headers, data, json, timeout, allowRedirects, stream, cookies)

fun head(
    url: String,
    params: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    timeout: Double = 30.0,
    allowRedirects: Boolean = true,
    cookies: Map<String, String>? = null,
) = request("HEAD", url, params, headers, null, null, timeout, allowRedirects, false, cookies)

fun patch(
    url: String,
    params: Map<String, String>? = null,
    headers: Map<String, String>? = null,
    data: Any? = null,
    json: Any? = null,
    timeout: Double = 30.0,
    allowRedirects: Boolean = true,
    stream: Boolean = false,
    cookies: Map<String, String>? = null,
) = request("PATCH", url, params, headers, data, json, timeout, allowRedirects, stream, cookies)
