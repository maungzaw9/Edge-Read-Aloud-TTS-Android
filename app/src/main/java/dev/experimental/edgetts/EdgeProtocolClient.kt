package dev.experimental.edgetts

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent WebSocket client: one socket for several SSML turns.
 * Changes in this build:
 *  - callbacks from a socket that is no longer the current one are ignored
 *    (so dropping an old socket can never fail a newer turn);
 *  - a socket idle for more than REUSE_MAX_IDLE_MS is not reused;
 *  - if the server sends nothing for IDLE_TIMEOUT_MS before any audio,
 *    one silent reconnect is attempted before failing.
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

    @Volatile
    var lastReuse: Boolean = false
        private set

    private val connLock = Any()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var connectionOpen = false

    @Volatile
    private var current: Session? = null

    @Volatile
    private var lastActivityMs: Long = 0L

    private var connectionId: String = newId()
    private var muid: String = drm.newMuid()

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
        synchronized(connLock) {
            connectionOpen = false
            current = null
            val s = socket
            socket = null
            runCatching { s?.close(1000, "shutdown") }
        }
    }

    inner class PreparedTurn internal constructor(private val session: Session) : Cancellable {
        fun start() = session.begin()
        override fun cancel() = session.cancel()
    }

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
        private val requestId = newId()

        private val finished = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val receivedAudio = AtomicBoolean(false)
        private val attempts = AtomicInteger(0)
        private val gecRefreshes = AtomicInteger(0)

        private val diagLines = CopyOnWriteArrayList<String>()
        private val binaryFramesSeen = AtomicInteger(0)
        private val pathSequence = ArrayList<String>()

        private var watchdog: ScheduledFuture<*>? = null

        fun isDone(): Boolean = finished.get() || cancelled.get()

        private fun logDiag(line: String) {
            diagLines += line
        }

        private fun flushDiag() {
            val text = diagLines.joinToString("\n")
            if (text.isBlank()) return
            DIAG_IO.execute { onDiagnostic?.invoke(text) }
        }

        fun begin() {
            synchronized(connLock) {
                current = this
                val open = socket
                val idleMs = System.currentTimeMillis() - lastActivityMs
                if (connectionOpen && open != null && idleMs <= REUSE_MAX_IDLE_MS) {
                    lastReuse = true
                    AppLog.i(TAG) { "persist hit request=$requestId" }
                    logDiag("persist=hit")
                    scheduleWatchdog()
                    if (!open.send(ssmlFrame())) {
                        dropSocketLocked()
                        lastReuse = false
                        AppLog.i(TAG) { "persist send failed, reconnecting" }
                        connectLocked()
                    }
                    return
                }
                if (open != null) {
                    AppLog.i(TAG) { "persist stale idle=${idleMs}ms, reconnecting" }
                    dropSocketLocked()
                }
                lastReuse = false
                AppLog.i(TAG) { "persist miss, connecting request=$requestId" }
                logDiag("persist=miss")
                connectLocked()
            }
        }

        private fun connectLocked() {
            connectionId = newId()
            muid = drm.newMuid()
            val nowSeconds = drm.unixSeconds()
            val gec = drm.generateSecMsGec(nowSeconds, trustedClientToken)
            val url = drm.websocketUrl(
                wsBaseUrl, connectionId, trustedClientToken, secMsGecVersion, nowSeconds
            )
            val request = Request.Builder().url(url)
            for ((k, v) in drm.handshakeHeaders(userAgent, origin, muid)) {
                request.header(k, v)
            }
            logDiag(
                drm.handshakeDiagLine(
                    gec, muid, origin, userAgent, secMsGecVersion,
                    attempts.get() + 1 + gecRefreshes.get()
                )
            )
            if (EdgeProtocolConstants.DEBUG_PROTOCOL) {
                Log.d(TAG, "handshake: formato=$outputFormat voz=$voice")
            }
            socket = client.newWebSocket(request.build(), listener)
            scheduleWatchdog()
        }

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            watchdog?.cancel(false)
            synchronized(connLock) { dropSocketLocked() }
            fail(SynthesisCancelledException())
        }

        fun onSocketOpen(webSocket: WebSocket) {
            bumpWatchdog()
            webSocket.send(speechConfigFrame())
            webSocket.send(ssmlFrame())
        }

        fun onText(text: String) {
            bumpWatchdog()
            val headers = AudioFrameParser.parseTextFrameHeaders(text)
            val path = AudioFrameParser.pathOf(headers)
            if (path != null) synchronized(pathSequence) { pathSequence += path }
            when (path) {
                EdgeProtocolConstants.PATH_TURN_START ->
                    AppLog.i(TAG) { "turn.start recibido persist=${if (lastReuse) "hit" else "miss"}" }

                EdgeProtocolConstants.PATH_TURN_END -> {
                    if (receivedAudio.get()) {
                        finishOk()
                    } else {
                        logDiag(
                            "turn.end sin audio · frames binarios recibidos=" +
                                binaryFramesSeen.get() + " · formato=$outputFormat"
                        )
                        fail(NoAudioReceivedException())
                    }
                }

                EdgeProtocolConstants.PATH_AUDIO_METADATA -> {
                    val body = AudioFrameParser.bodyOf(text)
                    if (body.isNotEmpty()) onAudioMetadata(body)
                }

                EdgeProtocolConstants.PATH_RESPONSE -> {
                    val body = AudioFrameParser.bodyOf(text)
                    val status = STATUS_JSON.find(body)?.groupValues?.get(1)?.toIntOrNull()
                    logDiag("Path:response status=${status ?: "n/d"} bytes=${body.length}")
                    if (status != null && status >= 400) {
                        fail(ProviderHttpException(status, "status $status"))
                    }
                }

                else -> Unit
            }
        }

        fun onBinary(bytes: ByteString) {
            bumpWatchdog()
            val n = binaryFramesSeen.incrementAndGet()
            val frame = AudioFrameParser.parseBinaryFrame(bytes)
            if (n <= 3) {
                logDiag("binario#$n len=${bytes.size} path=${frame.path ?: "n/d"}")
            }
            if (frame.unexpectedAudioType()) {
                logDiag(
                    "Content-Type inesperado=${frame.contentType()} bytes=${frame.payloadLength}"
                )
            }
            if (frame.path == EdgeProtocolConstants.PATH_AUDIO) {
                if (frame.payloadLength > 0 && !cancelled.get()) {
                    receivedAudio.set(true)
                    onEncodedAudioChunk(frame.raw, frame.payloadOffset, frame.payloadLength)
                }
            } else if (frame.path == EdgeProtocolConstants.PATH_AUDIO_METADATA) {
                val body = String(frame.payload, Charsets.UTF_8)
                if (body.isNotEmpty()) onAudioMetadata(body)
            }
        }

        fun onPeerClosed(code: Int, reason: String) {
            if (finished.get() || cancelled.get()) return
            val seq = synchronized(pathSequence) { pathSequence.toString() }
            logDiag(
                "EOF cierre=$code razón='${reason.take(40)}' · paths=[$seq]" +
                    " · binarios=${binaryFramesSeen.get()} · audio=${receivedAudio.get()}" +
                    " · formato=$outputFormat"
            )
            fail(
                IOException(
                    "EOF: el servidor cerró el WebSocket (código $code) " +
                        "antes de completar la síntesis"
                )
            )
        }

        fun onPeerFailure(t: Throwable, response: Response?) {
            if (finished.get() || cancelled.get()) return
            val code = response?.code ?: -1
            runCatching { response?.body?.close() }

            if (code in EdgeProtocolConstants.PERMANENT_HTTP_ERRORS) {
                if (code == 403 && !receivedAudio.get() &&
                    gecRefreshes.getAndIncrement() == 0
                ) {
                    if (drm.tryUpdateSkewFromDate(response?.header("Date"))) {
                        Log.w(
                            TAG,
                            "403 en handshake: deriva ajustada " +
                                "(skew=${drm.skewSeconds()} s), renovación única"
                        )
                        logDiag("403 → renovación de contexto (deriva=${drm.skewSeconds()}s)")
                        synchronized(connLock) {
                            dropSocketLocked()
                            connectLocked()
                        }
                        return
                    }
                    logDiag("403 → Date ausente o absurda, sin renovación")
                }
                logDiag("FALLO HTTP $code")
                fail(ProviderHttpException(code, "HTTP $code"))
                return
            }

            if (!receivedAudio.get() &&
                attempts.incrementAndGet() <= EdgeProtocolConstants.MAX_AUTO_RETRIES
            ) {
                Log.w(TAG, "Fallo de red antes del audio: reintento único")
                synchronized(connLock) {
                    dropSocketLocked()
                    connectLocked()
                }
                return
            }

            fail(
                when {
                    t is SocketTimeoutException -> t
                    code > 0 -> ProviderHttpException(code, "HTTP $code")
                    else -> t
                }
            )
        }

        private fun bumpWatchdog() {
            if (finished.get() || cancelled.get()) return
            lastActivityMs = System.currentTimeMillis()
            scheduleWatchdog()
        }

        private fun scheduleWatchdog() {
            watchdog?.cancel(false)
            watchdog = WATCHDOG.schedule(
                {
                    if (!isDone()) {
                        if (!receivedAudio.get() &&
                            attempts.incrementAndGet() <= EdgeProtocolConstants.MAX_AUTO_RETRIES
                        ) {
                            AppLog.i(TAG) { "watchdog: no data, one silent retry" }
                            logDiag("watchdog sin datos → reconexión única")
                            synchronized(connLock) {
                                dropSocketLocked()
                                connectLocked()
                            }
                        } else {
                            synchronized(connLock) { dropSocketLocked() }
                            fail(
                                TimeoutException(
                                    "La síntesis estuvo " +
                                        (IDLE_TIMEOUT_MS / 1000) +
                                        " s sin datos del servidor"
                                )
                            )
                        }
                    }
                },
                IDLE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        }

        private fun finishOk() {
            if (!finished.compareAndSet(false, true)) return
            watchdog?.cancel(false)
            lastActivityMs = System.currentTimeMillis()
            synchronized(connLock) {
                if (current === this) current = null
            }
            flushDiag()
            onComplete()
        }

        private fun fail(t: Throwable) {
            if (!finished.compareAndSet(false, true)) return
            watchdog?.cancel(false)
            synchronized(connLock) {
                if (current === this) current = null
                if (t !is SynthesisCancelledException) dropSocketLocked()
            }
            flushDiag()
            onError(t)
        }

        private fun ssmlFrame(): String {
            val ssml = SsmlBuilder.build(voice, locale, rate, pitch, text)
            return buildString {
                append("X-RequestId:").append(requestId)
                append(EdgeProtocolConstants.CRLF)
                append("Content-Type:application/ssml+xml")
                append(EdgeProtocolConstants.CRLF)
                append("X-Timestamp:").append(drm.jsTimestamp(withTrailingZ = true))
                append(EdgeProtocolConstants.CRLF)
                append("Path:").append(EdgeProtocolConstants.PATH_SSML)
                append(EdgeProtocolConstants.CRLF)
                append(EdgeProtocolConstants.CRLF)
                append(ssml)
            }
        }
    }

    private fun dropSocketLocked() {
        connectionOpen = false
        val s = socket
        socket = null
        runCatching { s?.cancel() }
    }

    private fun speechConfigFrame(): String {
        val config = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "synthesis",
                    JSONObject().put(
                        "audio",
                        JSONObject()
                            .put(
                                "metadataoptions",
                                JSONObject()
                                    .put("sentenceBoundaryEnabled", "false")
                                    .put("wordBoundaryEnabled", "false")
                            )
                            .put("outputFormat", outputFormat)
                    )
                )
            )
        return buildString {
            append("X-Timestamp:").append(drm.jsTimestamp(withTrailingZ = false))
            append(EdgeProtocolConstants.CRLF)
            append("Content-Type:application/json; charset=utf-8")
            append(EdgeProtocolConstants.CRLF)
            append("Path:").append(EdgeProtocolConstants.PATH_SPEECH_CONFIG)
            append(EdgeProtocolConstants.CRLF)
            append(EdgeProtocolConstants.CRLF)
            append(config.toString())
            append(EdgeProtocolConstants.CRLF)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            var turn: Session? = null
            var stale = false
            synchronized(connLock) {
                if (socket === webSocket) {
                    connectionOpen = true
                    turn = current
                } else {
                    stale = true
                }
            }
            if (stale) return
            val t = turn
            t?.onSocketOpen(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            current?.onText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (socket !== webSocket) return
            current?.onBinary(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            var turn: Session? = null
            var stale = false
            synchronized(connLock) {
                if (socket === webSocket) {
                    connectionOpen = false
                    socket = null
                    turn = current
                } else {
                    stale = true
                }
            }
            if (stale) return
            val t = turn
            if (t != null && !t.isDone()) {
                t.onPeerClosed(code, reason)
            } else {
                AppLog.i(TAG) { "socket idle closed code=$code reason='${reason.take(40)}'" }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            var turn: Session? = null
            var stale = false
            synchronized(connLock) {
                if (socket === webSocket) {
                    connectionOpen = false
                    socket = null
                    turn = current
                } else {
                    stale = true
                }
            }
            if (stale) {
                runCatching { response?.body?.close() }
                return
            }
            val s = turn
            if (s != null && !s.isDone()) {
                s.onPeerFailure(t, response)
            } else {
                AppLog.i(TAG) { "socket idle failure ${t.javaClass.simpleName}" }
            }
        }
    }

    companion object {
        private const val TAG = "EdgeTtsClient"

        /** Silence from the server (no frame at all) longer than this = timeout. */
        private const val IDLE_TIMEOUT_MS: Long = 30_000L

        /** A persistent socket idle longer than this is dropped, not reused. */
        private const val REUSE_MAX_IDLE_MS: Long = 10_000L

        private val WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "edge-tts-watchdog").apply { isDaemon = true }
            }

        private val DIAG_IO = Executors.newSingleThreadExecutor { r ->
            Thread(r, "edge-tts-diag").apply { isDaemon = true }
        }

        private val STATUS_JSON = Regex("\"status\"\\s*:\\s*\"?(\\d{3})\"?")

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
