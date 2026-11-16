package com.lagradost.shiro.utils.zg

import android.util.Log
import com.lagradost.shiro.utils.mvvm.logError
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Runs Zangetsu "provider" scripts (plain JavaScript files exposing search/getDetail/getVideoSources/...)
 * on Rhino. Providers are written against a small host API: Promise-returning `fetch`, timers, a few crypto
 * and html helpers. This mirrors the bootstrap Zangetsu itself injects (see its js_bootstrap.dart), so
 * unmodified provider files from any Zangetsu repository run here.
 *
 * Rhino contexts are thread-bound, so every call runs on one dedicated big-stack thread; network work is
 * done on helper threads and fed back into the JS event loop through [inbox].
 */
object ZangetsuEngine {
    private const val TAG = "ZangetsuEngine"

    private val BOOTSTRAP = """
var globalThis = this;
var __pendingFetches = {}, __fetchSeq = 0, __pendingCrypto = {}, __cryptoSeq = 0, __timers = {}, __timerSeq = 0;
globalThis.__settings = {};
function __resolveFetch(id, json) { var p = __pendingFetches[id]; if (!p) return; delete __pendingFetches[id]; try { p.resolve(JSON.parse(json)); } catch (e) { p.reject('Invalid fetch response JSON: ' + e); } }
function __rejectFetch(id, why) { var p = __pendingFetches[id]; if (!p) return; delete __pendingFetches[id]; p.reject(why); }
function __resolveCrypto(id, v) { var p = __pendingCrypto[id]; if (!p) return; delete __pendingCrypto[id]; p.resolve(v); }
function __rejectCrypto(id, why) { var p = __pendingCrypto[id]; if (!p) return; delete __pendingCrypto[id]; p.reject(why); }
function __fireTimer(id) { var fn = __timers[id]; if (!fn) return; delete __timers[id]; try { fn(); } catch (e) {} }
function __crypto(op, payload) {
  __cryptoSeq += 1; var id = 'c' + __cryptoSeq; var msg = { id: id, op: op };
  for (var k in payload) { if (payload.hasOwnProperty(k)) msg[k] = payload[k]; }
  var promise = new Promise(function (resolve, reject) { __pendingCrypto[id] = { resolve: resolve, reject: reject }; });
  sendMessage('crypto', JSON.stringify(msg));
  return promise;
}
function sha256Hex(message) { return __crypto('sha256', { message: String(message) }); }
function aesCtrDecrypt(o) { return __crypto('aesCtrDecrypt', { keyHex: o.keyHex, counterHex: o.counterHex, dataB64: o.dataB64 }); }
var __B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function base64ToBytes(b64) {
  var lookup = {}; for (var i = 0; i < __B64.length; i++) lookup[__B64.charAt(i)] = i;
  var s = String(b64).replace(/[^A-Za-z0-9+\/]/g, ''); var out = []; var n = s.length;
  for (var j = 0; j < n; j += 4) {
    var e1 = lookup[s.charAt(j)], e2 = lookup[s.charAt(j + 1)], e3 = lookup[s.charAt(j + 2)], e4 = lookup[s.charAt(j + 3)];
    out.push((e1 << 2) | (e2 >> 4));
    if (j + 2 < n) out.push(((e2 & 15) << 4) | (e3 >> 2));
    if (j + 3 < n) out.push(((e3 & 3) << 6) | e4);
  }
  return out;
}
function bytesToHex(bytes) { var h = ''; for (var i = 0; i < bytes.length; i++) { var x = (bytes[i] & 255).toString(16); h += x.length === 1 ? '0' + x : x; } return h; }
function bytesToB64(bytes) {
  var out = '', i = 0;
  while (i < bytes.length) {
    var c1 = bytes[i++] & 255, c2 = i < bytes.length ? bytes[i++] & 255 : NaN, c3 = i < bytes.length ? bytes[i++] & 255 : NaN;
    out += __B64.charAt(c1 >> 2) + __B64.charAt(((c1 & 3) << 4) | (c2 >> 4))
      + (isNaN(c2) ? '=' : __B64.charAt(((c2 & 15) << 2) | (c3 >> 6))) + (isNaN(c3) ? '=' : __B64.charAt(c3 & 63));
  }
  return out;
}
function fetch(url, opts) {
  opts = opts || {}; __fetchSeq += 1; var id = 'f' + __fetchSeq;
  var payload = { id: id, url: String(url), method: (opts.method || 'GET').toUpperCase(), headers: opts.headers || {},
    body: opts.body == null ? null : (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)),
    timeoutMs: (typeof opts.timeoutMs === 'number' && opts.timeoutMs > 0) ? opts.timeoutMs : 0,
    followRedirects: opts.followRedirects === false ? false : true };
  var promise = new Promise(function (resolve, reject) { __pendingFetches[id] = { resolve: resolve, reject: reject }; });
  sendMessage('fetch', JSON.stringify(payload));
  return promise.then(function (res) {
    return { ok: res.status >= 200 && res.status < 300, status: res.status, statusText: res.statusText || '',
      headers: res.headers || {}, url: res.url || url,
      text: function () { return Promise.resolve(res.body || ''); },
      json: function () { try { return Promise.resolve(JSON.parse(res.body || 'null')); } catch (e) { return Promise.reject('Invalid JSON: ' + e); } },
      body: res.body || '' };
  });
}
function setTimeout(fn, ms) { __timerSeq += 1; var id = 't' + __timerSeq; __timers[id] = fn; sendMessage('timer', JSON.stringify({ id: id, ms: ms || 0 })); return id; }
function clearTimeout(id) { delete __timers[id]; }
function setInterval(fn, ms) { return setTimeout(fn, ms); }
function clearInterval(id) { clearTimeout(id); }
var console = { log: function () { __log('log', arguments); }, warn: function () { __log('warn', arguments); }, error: function () { __log('error', arguments); }, info: function () { __log('info', arguments); }, debug: function () {} };
function __log(level, args) { try { var p = []; for (var i = 0; i < args.length; i++) { var a = args[i]; p.push(typeof a === 'string' ? a : JSON.stringify(a)); } sendMessage('console', JSON.stringify({ level: level, message: p.join(' ') })); } catch (e) {} }
function htmlText(html) {
  if (!html) return '';
  return String(html).replace(/<[^>]*>/g, '')
    .replace(/&#x([0-9a-fA-F]+);/g, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&apos;/g, "'")
    .replace(/\s+/g, ' ').trim();
}
function absUrl(href, base) {
  if (!href) return '';
  if (/^https?:\/\//i.test(href)) return href;
  if (href.indexOf('//') === 0) return 'https:' + href;
  if (!base) return href;
  if (href.charAt(0) === '/') { var m = base.match(/^(https?:\/\/[^\/]+)/i); return m ? m[1] + href : href; }
  return base.replace(/\/$/, '') + '/' + href;
}
function extractVideo(embedUrl, opts) {
  var m = String(embedUrl).match(/^https?:\/\/([^\/]+)/i); var host = m ? m[1].toLowerCase().replace(/^www\./, '') : '';
  var ex = globalThis.__extractors && globalThis.__extractors[host];
  if (!ex) return Promise.reject('No extractor for host: ' + host);
  try { return Promise.resolve(ex.extract(embedUrl, opts || {})); } catch (e) { return Promise.reject(String((e && e.message) || e)); }
}
globalThis.__extractors = {};
function __nkCall(method, argsJson) {
  var st = globalThis.__nkState = { done: false };
  try {
    var args = JSON.parse(argsJson || '[]'); var fn = globalThis[method];
    if (typeof fn !== 'function') { st.done = true; st.ok = false; st.error = 'Provider missing method: ' + method; return; }
    Promise.resolve(fn.apply(null, args)).then(
      function (v) { st.value = JSON.stringify(v == null ? null : v); st.ok = true; st.done = true; },
      function (e) { st.error = (e && e.message) ? String(e.message) : (typeof e === 'string' ? e : String(e)); st.ok = false; st.done = true; });
  } catch (e) { st.error = String((e && e.message) || e); st.ok = false; st.done = true; }
}
"""

    private class Msg(val js: String)

    /** One persistent JS scope per source, only ever touched on [exec]. */
    private val scopes = HashMap<String, ScriptableObject>()
    private val scopeVersion = HashMap<String, Int>()
    private val inbox = LinkedBlockingQueue<Msg>()

    private val exec = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
    ) { r -> Thread(null, r, "ZangetsuJs", 16L * 1024 * 1024) }

    private fun jsStr(s: String) = JSONObject.quote(s)

    private class SendMessage(private val scopeKey: String) : org.mozilla.javascript.BaseFunction() {
        override fun call(
            cx: Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable, args: Array<Any?>
        ): Any? {
            try {
                val channel = Context.toString(args.getOrNull(0))
                val payload = JSONObject(Context.toString(args.getOrNull(1)))
                handleMessage(scopeKey, channel, payload)
            } catch (t: Throwable) {
                logError(t)
            }
            return Context.getUndefinedValue()
        }
    }

    private fun handleMessage(source: String, channel: String, p: JSONObject) {
        when (channel) {
            "fetch" -> thread(name = "ZangetsuFetch") {
                val id = p.getString("id")
                val result = try {
                    val headers = HashMap<String, String>()
                    p.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
                    val body = if (p.isNull("body")) null else p.getString("body")
                    val timeout = p.optLong("timeoutMs", 0).let { if (it > 0) it / 1000.0 else 30.0 }
                    val follow = p.optBoolean("followRedirects", true)
                    val method = p.optString("method", "GET")
                    val url = p.getString("url")
                    val res = when (method) {
                        "GET" -> khttp.get(url, headers = headers, timeout = timeout, allowRedirects = follow)
                        "POST" -> khttp.post(url, headers = headers, data = body, timeout = timeout, allowRedirects = follow)
                        "PUT" -> khttp.put(url, headers = headers, data = body, timeout = timeout, allowRedirects = follow)
                        "DELETE" -> khttp.delete(url, headers = headers, data = body, timeout = timeout, allowRedirects = follow)
                        "HEAD" -> khttp.head(url, headers = headers, timeout = timeout, allowRedirects = follow)
                        else -> khttp.get(url, headers = headers, timeout = timeout, allowRedirects = follow)
                    }
                    JSONObject().apply {
                        put("status", res.statusCode)
                        put("statusText", "")
                        put("url", res.url)
                        put("headers", JSONObject(res.headers))
                        put("body", res.text)
                    }.toString()
                } catch (t: Throwable) {
                    inbox.add(Msg("__rejectFetch(${jsStr(id)}, ${jsStr(t.message ?: t.javaClass.simpleName)})"))
                    null
                }
                if (result != null) inbox.add(Msg("__resolveFetch(${jsStr(id)}, ${jsStr(result)})"))
            }
            "timer" -> thread(name = "ZangetsuTimer") {
                try {
                    Thread.sleep(p.optLong("ms", 0).coerceIn(0, 60_000))
                } catch (_: InterruptedException) {
                }
                inbox.add(Msg("__fireTimer(${jsStr(p.getString("id"))})"))
            }
            "crypto" -> {
                val id = p.getString("id")
                try {
                    val out = when (p.getString("op")) {
                        "sha256" -> MessageDigest.getInstance("SHA-256")
                            .digest(p.getString("message").toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        "aesCtrDecrypt" -> {
                            val key = hexToBytes(p.getString("keyHex"))
                            val iv = hexToBytes(p.getString("counterHex"))
                            val data = android.util.Base64.decode(p.getString("dataB64"), android.util.Base64.DEFAULT)
                            val c = Cipher.getInstance("AES/CTR/NoPadding")
                            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            String(c.doFinal(data), Charsets.UTF_8)
                        }
                        else -> throw IllegalArgumentException("Unknown crypto op")
                    }
                    inbox.add(Msg("__resolveCrypto(${jsStr(id)}, ${jsStr(out)})"))
                } catch (t: Throwable) {
                    inbox.add(Msg("__rejectCrypto(${jsStr(id)}, ${jsStr(t.message ?: "crypto error")})"))
                }
            }
            "console" -> Log.d(TAG, "[$source] ${p.optString("level")}: ${p.optString("message")}")
        }
    }

    private fun hexToBytes(h: String) = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun scopeFor(cx: Context, sourceId: String, script: String, version: Int): ScriptableObject {
        scopes[sourceId]?.let { if (scopeVersion[sourceId] == version) return it }
        val scope = cx.initStandardObjects()
        ScriptableObject.putProperty(scope, "sendMessage", SendMessage(sourceId))
        ScriptableObject.putProperty(scope, "__SOURCE_ID", sourceId)
        cx.evaluateString(scope, BOOTSTRAP, "zg-bootstrap", 1, null)
        cx.evaluateString(scope, script, "zg-$sourceId", 1, null)
        scopes[sourceId] = scope
        scopeVersion[sourceId] = version
        return scope
    }

    /**
     * Calls `method(...args)` of the given provider script and returns the JSON the (promise of the) result
     * serialises to. Blocking; throws on provider error or timeout.
     */
    fun call(
        sourceId: String,
        script: String,
        scriptVersion: Int,
        method: String,
        argsJson: String,
        timeoutMs: Long = 45_000
    ): String? {
        val task = Callable<String?> {
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1 // interpreted: dex can't load generated classes
                cx.languageVersion = Context.VERSION_ES6
                inbox.clear()
                val scope = scopeFor(cx, sourceId, script, scriptVersion)
                val callFn = ScriptableObject.getProperty(scope, "__nkCall") as Function
                callFn.call(cx, scope, scope, arrayOf(method, argsJson))
                val deadline = System.currentTimeMillis() + timeoutMs
                var state = ScriptableObject.getProperty(scope, "__nkState") as org.mozilla.javascript.Scriptable
                while (true) {
                    cx.processMicrotasks()
                    if (Context.toBoolean(ScriptableObject.getProperty(state, "done"))) break
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw RuntimeException("$method timed out")
                    val msg = inbox.poll(minOf(left, 100L), TimeUnit.MILLISECONDS)
                    if (msg != null) {
                        cx.evaluateString(scope, msg.js, "zg-msg", 1, null)
                        // drain whatever else arrived meanwhile
                        while (true) {
                            val more = inbox.poll() ?: break
                            cx.evaluateString(scope, more.js, "zg-msg", 1, null)
                        }
                    }
                    state = ScriptableObject.getProperty(scope, "__nkState") as org.mozilla.javascript.Scriptable
                }
                cx.processMicrotasks()
                if (!Context.toBoolean(ScriptableObject.getProperty(state, "ok"))) {
                    throw RuntimeException(Context.toString(ScriptableObject.getProperty(state, "error")))
                }
                val v = ScriptableObject.getProperty(state, "value")
                if (v == org.mozilla.javascript.Scriptable.NOT_FOUND || v == Context.getUndefinedValue()) null
                else Context.toString(v)
            } finally {
                Context.exit()
            }
        }
        return try {
            exec.submit(task).get(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    /** Drops the cached scope of a source (after uninstall/update). */
    fun forget(sourceId: String) {
        exec.execute { scopes.remove(sourceId); scopeVersion.remove(sourceId) }
    }
}
