package dev.experimental.edgetts

import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * Uses the same route as the Bing Translator page itself:
 *   1. GET  https://www.bing.com/translator -> IG, IID, abuse-prevention key+token
 *   2. POST https://www.bing.com/tfettts?isVertical=1&IG=..&IID=..
 *           form: ssml, token, key           -> MP3 audio (streamed to the caller)
 *
 * Unofficial: may stop working whenever Microsoft changes the web page.
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

    /** true when the page config of the last turn came from the cache. */
    @Volatile
    var lastReuse: Boolean = false
        private set

    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(MemoryCookieJar())
        .followRedirects(true)
        .followSslRedirects(true)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private class PageConfig(
        val ig: String,
        val iid: String,
        val key: String,
        val token: String,
        val host: String,
        val loadedAt: Long
    )

    private val configLock = Any()
    private var config: PageConfig? = null
    private var iidCounter = 0
    private var pageHtml: String = ""
    private var pageUrlFinal: HttpUrl? = null

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

    // ----------------------------------------------------------- page config

    /** Returns the cached page config, or loads the Bing page again. */
    private fun getConfig(forceRefresh: Boolean, diag: (String) -> Unit): PageConfig =
        synchronized(configLock) {
            val now = System.currentTimeMillis()
            val cached = config
            if (!forceRefresh && cached != null && now - cached.loadedAt < MAX_CONFIG_AGE_MS) {
                lastReuse = true
                cached
            } else {
                lastReuse = false
                loadPageConfigLocked(diag)
            }
        }

    private fun loadPageConfigLocked(diag: (String) -> Unit): PageConfig {
        val req = Request.Builder()
            .url(BING_PAGE_URL)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val result: PageConfig = http.newCall(req).execute().use { resp ->
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
            pageHtml = html
            pageUrlFinal = finalUrl
            iidCounter = 0
            PageConfig(
                ig = ig,
                iid = iid,
                key = abuse.groupValues[1],
                token = abuse.groupValues[2],
                host = finalUrl.host,
                loadedAt = System.currentTimeMillis()
            )
        }
        config = result
        return result
    }

    private fun nextIidSuffix(): Int = synchronized(configLock) {
        iidCounter += 1
        iidCounter
    }

    // ------------------------------------------------------------- discovery

    /**
     * Diagnostic only: when the speech route fails we put short excerpts of the
     * speech-related settings found in the Bing page into the diagnostics.
     */
    private fun describePageLocked(diag: (String) -> Unit) {
        try {
            val html = pageHtml
            for (kw in listOf("params_TextToSpeechService", "params_SpeechServiceHelper")) {
                val i = html.indexOf(kw)
                if (i >= 0) {
                    val ctx = html.substring(i, minOf(html.length, i + 220))
                        .replace("\n", " ").replace("\r", " ")
                    diag("html: $ctx")
                }
            }
        } catch (e: Exception) {
            diag("describe error ${e.javaClass.simpleName}")
        }
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
                var retry = false
                for (attempt in 0..1) {
                    if (cancelled.get()) return
                    val cfg = getConfig(attempt > 0) { logDiag(it) }
                    retry = speak(cfg)
                    if (!retry) break
                    logDiag("request rejected, reloading the Bing page")
                }
                if (cancelled.get()) return
                if (retry) {
                    fail(ProviderHttpException(403, "Bing rejected the request twice"))
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

        /**
         * Returns false when audio was delivered (or a hard error was thrown),
         * true when the request should be repeated with a fresh page config.
         */
        private fun speak(cfg: PageConfig): Boolean {
            val ssml = SsmlBuilder.build(voice, locale, rate, pitch, text)
            val url = "https://${cfg.host}/tfettts?isVertical=1&IG=${cfg.ig}" +
                "&IID=${cfg.iid}.${nextIidSuffix()}"
            val form = FormBody.Builder()
                .add("ssml", ssml)
                .add("token", cfg.token)
                .add("key", cfg.key)
                .build()
            val req = Request.Builder()
                .url(url)
                .post(form)
                .header("User-Agent", userAgent)
                .header("Origin", "https://${cfg.host}")
                .header("Referer", BING_PAGE_URL)
                .header("Accept", "*/*")
                .build()
            val call = http.newCall(req)
            activeCall = call
            val needsRetry: Boolean = call.execute().use { resp ->
                val type = resp.header("Content-Type").orEmpty()
                logDiag("tfettts http=${resp.code} type=$type voice=$voice")
                if (resp.code == 400 || resp.code == 401 || resp.code == 403) {
                    return@use true
                }
                if (!resp.isSuccessful) {
                    synchronized(configLock) { describePageLocked { logDiag(it) } }
                    throw ProviderHttpException(resp.code, "TTS HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IOException("empty TTS response")
                if (type.contains("json", true) || type.contains("text", true)) {
                    val msg = body.string().take(200)
                    val code = try {
                        JSONObject(msg).optInt("statusCode", -1)
                    } catch (e: Exception) {
                        -1
                    }
                    logDiag("tfettts text reply: $msg")
                    if (code == 205 || code == 401 || code == 400) {
                        return@use true
                    }
                    throw IOException("TTS reply is not audio: $msg")
                }
                val input = body.byteStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    if (cancelled.get()) return@use false
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        total += n
                        receivedAudio.set(true)
                        onEncodedAudioChunk(buf.copyOf(n), 0, n)
                    }
                }
                logDiag("audio bytes=$total")
                false
            }
            return needsRetry
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

        /** The page token is valid for about an hour; refresh well before. */
        private const val MAX_CONFIG_AGE_MS = 40 * 60 * 1000L

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
