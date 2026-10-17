package dev.experimental.edgetts

import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drop-in replacement of the old WebSocket client (same public API).
 *
 * The Edge Read Aloud WebSocket stopped accepting the old Sec-MS-GEC token
 * (Oct 2026). This version does what the Bing Translator web page does:
 *   1. GET  https://www.bing.com/translator  -> IG, IID, abuse-prevention token
 *   2. POST /tfetspktok                      -> short-lived speech token + region
 *   3. POST https://<region>.tts.speech.microsoft.com/cognitiveservices/v1
 *            with the SSML  -> MP3 audio (streamed to the caller)
 *
 * Unofficial: it may stop working whenever Microsoft changes the web page.
 */
class EdgeProtocolClient(
    private val client: OkHttpClient,
    private val drm: EdgeDrm = SharedProtocol.drm,
    private val wsBaseUrl: String = EdgeProtocolConstants.WS_BASE_URL,
    private val trustedClientToken: String = EdgeProtocolConstants.TRUSTED_CLIENT_TOKEN,
    private val secMsGecVersion: String = EdgeProtocolConstants.CLIENT_VERSION,
    private val userAgent: String = EdgeProtocolConstants.DEFAULT_USER_AGENT,
    private val origin: String = EdgeProtocolConstants.DEFAULT_ORIGIN,
    private val outputFormat: String = EdgeProtocolConstants.OUTPUT_FORMAT_MP3,
    private val onDiagnostic: ((String) -> Unit)? = null
) : TtsProvider {

    val fingerprint: ConnectionFingerprint = ConnectionFingerprint(
        wsUrl = wsBaseUrl,
        userAgent = userAgent,
        origin = origin,
        outputFormat = outputFormat,
        token = trustedClientToken
    )

    /** true when the speech token of the last turn came from the cache. */
    @Volatile
    var lastReuse: Boolean = false
        private set

    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(MemoryCookieJar())
        .followRedirects(true)
        .followSslRedirects(true)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private class Auth(val token: String, val region: String)

    private val authLock = Any()
    private var pageIg: String? = null
    private var pageIid: String? = null
    private var pageKey: String? = null
    private var pageToken: String? = null
    private var iidCounter = 0
    private var authToken: String? = null
    private var authRegion: String? = null
    private var authExpiresAt: Long = 0L

    @Volatile
    private var pageHost: String = "www.bing.com"

    override fun synthesize(
        text: String,
        voice: String,
        locale: String,
        rate: String,
        pitch: String,
        onEncodedAudioChunk: (ByteArray, Int, Int) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ): Cancellable {
        val turn = prepare(text, voice, locale, rate, pitch, onEncodedAudioChunk, onComplete, onError)
        turn.start()
        return turn
    }

    fun prepare(
        text: String,
        voice: String,
        locale: String,
        rate: String,
        pitch: String,
        onEncodedAudioChunk: (ByteArray, Int, Int) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit,
        onAudioMetadata: (String) -> Unit = {}
    ): PreparedTurn {
        val session = Session(
            text, voice, locale, rate, pitch,
            onEncodedAudioChunk, onComplete, onError, onAudioMetadata
        )
        return PreparedTurn(session)
    }

    fun shutdown() {
        // Nothing persistent to close: every turn is a plain HTTPS request.
    }

    inner class PreparedTurn internal constructor(private val session: Session) : Cancellable {
        fun start() = session.begin()
        override fun cancel() = session.cancel()
    }

    // ------------------------------------------------------------ token flow

    private fun getAuth(forceRefresh: Boolean, diag: (String) -> Unit): Auth =
        synchronized(authLock) {
            val now = System.currentTimeMillis()
            val cached = authToken
            if (!forceRefresh && cached != null && now < authExpiresAt - 30_000L) {
                lastReuse = true
                Auth(cached, authRegion ?: "southeastasia")
            } else {
                lastReuse = false
                authToken = null
                loadPageConfigLocked(diag)
                requestTokenLocked(diag)
            }
        }

    private fun loadPageConfigLocked(diag: (String) -> Unit) {
        val req = Request.Builder()
            .url(BING_PAGE_URL)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url
            val html = resp.body?.string().orEmpty()
            diag("page http=${resp.code} host=${finalUrl.host} bytes=${html.length}")
            if (!resp.isSuccessful) {
                throw ProviderHttpException(resp.code, "Bing page HTTP ${resp.code}")
            }
            val ig = IG_RE.find(html)?.groupValues?.get(1)
            val iid = IID_RE.find(html)?.groupValues?.get(1)
            val abuse = ABUSE_RE.find(html)
            if (ig == null || iid == null || abuse == null) {
                throw IOException(
                    "Bing page format not recognised (ig=${ig != null}, " +
                        "iid=${iid != null}, token=${abuse != null})"
                )
            }
            pageIg = ig
            pageIid = iid
            pageKey = abuse.groupValues[1]
            pageToken = abuse.groupValues[2]
            pageHost = finalUrl.host
            iidCounter = 0
        }
    }

    private fun requestTokenLocked(diag: (String) -> Unit): Auth {
        val ig = pageIg ?: throw IOException("missing IG")
        val iid = pageIid ?: throw IOException("missing IID")
        iidCounter += 1
        val url = "https://$pageHost/tfetspktok?isVertical=1&IG=$ig&IID=$iid.$iidCounter"
        val form = FormBody.Builder()
            .add("token", pageToken ?: "")
            .add("key", pageKey ?: "")
            .build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("User-Agent", userAgent)
            .header("Origin", "https://$pageHost")
            .header("Referer", BING_PAGE_URL)
            .header("Accept", "*/*")
            .build()
        val result: Auth = http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            diag("tfetspktok http=${resp.code} bytes=${text.length}")
            if (!resp.isSuccessful) {
                throw ProviderHttpException(resp.code, "speech token HTTP ${resp.code}")
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("speech token reply is not JSON: ${text.take(80)}")
            }
            val token = json.optString("token")
            val region = json.optString("region")
            if (token.isEmpty() || region.isEmpty()) {
                throw IOException("speech token reply missing fields: ${text.take(120)}")
            }
            val ttl = json.optLong("expiryDurationInMS", 600_000L)
            authToken = token
            authRegion = region
            authExpiresAt = System.currentTimeMillis() + ttl
            diag("token ok region=$region ttl=${ttl / 1000}s")
            Auth(token, region)
        }
        return result
    }

    // --------------------------------------------------------------- session

    inner class Session(
        private val text: String,
        private val voice: String,
        private val locale: String,
        private val rate: String,
        private val pitch: String,
        private val onEncodedAudioChunk: (ByteArray, Int, Int) -> Unit,
        private val onComplete: () -> Unit,
        private val onError: (Throwable) -> Unit,
        private val onAudioMetadata: (String) -> Unit
    ) {
        private val finished = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val receivedAudio = AtomicBoolean(false)
        private val diagLines = CopyOnWriteArrayList<String>()

        @Volatile
        private var activeCall: Call? = null

        fun isDone(): Boolean = finished.get() || cancelled.get()

        private fun logDiag(line: String) {
            diagLines += line
        }

        private fun flushDiag() {
            val joined = diagLines.joinToString("\n")
            if (joined.isBlank()) return
            DIAG_IO.execute { onDiagnostic?.invoke(joined) }
        }

        fun begin() {
            WORKER.execute { run() }
        }

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            runCatching { activeCall?.cancel() }
            fail(SynthesisCancelledException())
        }

        private fun run() {
            try {
                var code = 0
                for (attempt in 0..1) {
                    if (cancelled.get()) return
                    val auth = getAuth(attempt > 0) { logDiag(it) }
                    code = speak(auth)
                    if (code == 0) break
                    logDiag("token rejected http=$code, refreshing")
                }
                if (cancelled.get()) return
                if (code != 0) {
                    fail(ProviderHttpException(code, "HTTP $code: speech token rejected"))
                    return
                }
                if (!receivedAudio.get()) {
                    logDiag("no audio bytes received")
                    fail(NoAudioReceivedException())
                    return
                }
                finishOk()
            } catch (t: Throwable) {
                if (cancelled.get()) return
                logDiag("error ${t.javaClass.simpleName}: ${t.message?.take(160)}")
                fail(t)
            }
        }

        /** Returns 0 on success, or the HTTP code (401/403) when the token was rejected. */
        private fun speak(auth: Auth): Int {
            val ssml = SsmlBuilder.build(voice, locale, rate, pitch, text)
            val url = "https://${auth.region}.tts.speech.microsoft.com/cognitiveservices/v1"
            val req = Request.Builder()
                .url(url)
                .post(ssml.toRequestBody("application/ssml+xml; charset=utf-8".toMediaType()))
                .header("Authorization", "Bearer ${auth.token}")
                .header("X-Microsoft-OutputFormat", outputFormat)
                .header("User-Agent", userAgent)
                .header("Origin", "https://$pageHost")
                .build()
            val call = http.newCall(req)
            activeCall = call
            val result: Int = call.execute().use { resp ->
                logDiag("tts http=${resp.code} voice=$voice")
                if (resp.code == 401 || resp.code == 403) {
                    return@use resp.code
                }
                if (!resp.isSuccessful) {
                    val msg = resp.body?.string().orEmpty().take(120)
                    throw ProviderHttpException(resp.code, "TTS HTTP ${resp.code} $msg")
                }
                val body = resp.body ?: throw IOException("empty TTS response")
                val input = body.byteStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    if (cancelled.get()) return@use 0
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        total += n
                        receivedAudio.set(true)
                        onEncodedAudioChunk(buf.copyOf(n), 0, n)
                    }
                }
                logDiag("audio bytes=$total")
                0
            }
            return result
        }

        private fun finishOk() {
            if (!finished.compareAndSet(false, true)) return
            flushDiag()
            onComplete()
        }

        private fun fail(t: Throwable) {
            if (!finished.compareAndSet(false, true)) return
            flushDiag()
            onError(t)
        }
    }

    // ------------------------------------------------------------ cookie jar

    private class MemoryCookieJar : CookieJar {
        private val store = HashMap<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { ArrayList() }
            for (c in cookies) {
                list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                list.add(c)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val out = ArrayList<Cookie>()
            for (list in store.values) {
                for (c in list) {
                    if (c.expiresAt > now && c.matches(url)) out.add(c)
                }
            }
            return out
        }
    }

    companion object {
        private const val TAG = "EdgeTtsClient"
        private const val BING_PAGE_URL = "https://www.bing.com/translator"

        private val IG_RE = Regex("IG:\"([^\"]+)\"")
        private val IID_RE = Regex("data-iid=\"([^\"]+)\"")
        private val ABUSE_RE =
            Regex("params_AbusePreventionHelper\\s*=\\s*\\[\\s*(\\d+)\\s*,\\s*\"([^\"]+)\"")

        private val WORKER = Executors.newCachedThreadPool { r ->
            Thread(r, "bing-tts").apply { isDaemon = true }
        }

        private val DIAG_IO = Executors.newSingleThreadExecutor { r ->
            Thread(r, "edge-tts-diag").apply { isDaemon = true }
        }

        fun generateSecMsGec(unixSeconds: Long, trustedClientToken: String): String =
            EdgeDrm.generateSecMsGec(unixSeconds, trustedClientToken)

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }

    data class ConnectionFingerprint(
        val wsUrl: String,
        val userAgent: String,
        val origin: String,
        val outputFormat: String,
        val token: String
    )
}
