package dev.experimental.edgetts

/**
 * TODAS las constantes del protocolo no oficial de Edge Read Aloud viven aquí
 * y en ningún otro sitio. Nada de esto es una API pública de Microsoft:
 * cualquier valor puede cambiar sin aviso y la app debe fallar de forma
 * controlada cuando eso ocurra.
 *
 * Los marcados [VERIFICADO] provienen del cliente de referencia rany2/edge-tts
 * 7.2.8 y de capturas del wire real.
 */
object EdgeProtocolConstants {

    // ── Endpoints (configurables, nunca presentarlos como API oficial) ──────
    const val TRUSTED_CLIENT_TOKEN: String = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    const val VOICES_LIST_URL: String =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=" + TRUSTED_CLIENT_TOKEN

    const val WS_BASE_URL: String =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    /**
     * [VERIFICADO] Versión de cliente para Sec-MS-GEC-Version. Debe
     * coincidir con la build que el servidor reconoce (constants.py de
     * edge-tts: 143.0.3650.75). Configurable desde la app sin recompilar.
     */
    const val CLIENT_VERSION: String = "1-143.0.3650.75"

    /**
     * [VERIFICADO] Origin del lector inmersivo de Edge, que es quien consume
     * este endpoint. Un Origin genérico produce 403.
     */
    const val DEFAULT_ORIGIN: String =
        "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    /**
     * [VERIFICADO] User-Agent de Edge de ESCRITORIO con la versión MAYOR de
     * Chromium (143.0.0.0). No usar el UA del Edge de Android (EdgA/…).
     */
    const val DEFAULT_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    /**
     * Registro detallado del handshake para depurar 403. NUNCA imprime el
     * TrustedClientToken ni la URL completa: solo longitudes, prefijos del
     * hash y metadatos. Activar solo para depurar y volver a false.
     */
    const val DEBUG_PROTOCOL: Boolean = false

    // ── Paths de frame ───────────────────────────────────────────────────────
    const val PATH_SPEECH_CONFIG: String = "speech.config"
    const val PATH_SSML: String = "ssml"
    const val PATH_TURN_START: String = "turn.start"
    const val PATH_TURN_END: String = "turn.end"
    const val PATH_AUDIO: String = "audio"
    const val PATH_AUDIO_METADATA: String = "audio.metadata"
    const val PATH_RESPONSE: String = "response"

    // ── Cabeceras y formato de mensaje ──────────────────────────────────────
    const val HEADER_PATH: String = "Path"
    const val HEADER_REQUEST_ID: String = "X-RequestId"
    const val HEADER_TIMESTAMP: String = "X-Timestamp"
    const val CRLF: String = "\r\n"

    // ── Audio ────────────────────────────────────────────────────────────────
    /** [VERIFICADO] Formato primario: el único que usa el cliente de referencia. */
    const val OUTPUT_FORMAT_MP3: String = "audio-24khz-48kbitrate-mono-mp3"

    const val SAMPLE_RATE_HZ: Int = 16000

    /**
     * Tercer parámetro de SynthesisCallback.start(): NÚMERO de canales.
     * El SDK lo anota @IntRange(from = 1, to = 2). NO confundir con las
     * máscaras AudioFormat.CHANNEL_OUT_* de AudioTrack (MONO = 0x4): pasar
     * una máscara rompe el cálculo de frames del framework
     * (bytesPerFrame = channelCount × 2) y falla Lint con "must be ≤ 2".
     */
    const val CHANNEL_COUNT_MONO: Int = 1

    // ── Voz inicial ──────────────────────────────────────────────────────────
    const val DEFAULT_LOCALE: String = "es-MX"
    const val DEFAULT_VOICE: String = "es-MX-DaliaNeural"

    // ── Tiempos, reintentos y límites ───────────────────────────────────────
    const val CONNECT_TIMEOUT_MS: Long = 15_000L
    const val READ_TIMEOUT_MS: Long = 30_000L
    const val PING_INTERVAL_MS: Long = 20_000L
    /** Techo absoluto de una sesión (el watchdog de inactividad es más corto). */
    const val SYNTHESIS_TIMEOUT_MS: Long = 60_000L
    /** Sin frames: abortar. Se reprograma en cada mensaje del servidor. */
    const val IDLE_TIMEOUT_MS: Long = 20_000L

    /** Nunca más de un reintento automático, y solo si no se recibió audio. */
    const val MAX_AUTO_RETRIES: Int = 1

    const val MAX_SEGMENT_CHARS: Int = 4000
    /** Tope protocolario rany2/edge-tts: split_text_by_byte_length(text, 4096). */
    const val MAX_SEGMENT_BYTES: Int = 4096

    /** Errores permanentes: sin reintento automático (salvo renovación explícita). */
    val PERMANENT_HTTP_ERRORS: Set<Int> = setOf(401, 403, 404, 429)

    /**
     * Versión lógica del protocolo, parte de la clave de caché: si el formato
     * cambia, el audio cacheado deja de reutilizarse automáticamente.
     */
    const val PROTOCOL_VERSION: String = "edge-readaloud-v1-mp3-24k"
}
