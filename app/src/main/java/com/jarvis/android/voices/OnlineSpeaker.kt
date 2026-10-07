package com.jarvis.android.voices

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A voice that could not speak; the message is for the user. */
internal class VoiceException(message: String) : Exception(message)

/**
 * Reads text aloud in an online voice other than Gemini's (see MoreVoices.kt): hands 24 kHz mono PCM to [emit] as it comes.
 * [elevenKey] gives the user's ElevenLabs key when one is saved.
 */
internal class OnlineSpeaker(baseClient: OkHttpClient, private val elevenKey: () -> String?) {
    private val client = baseClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    /** Seconds to add to the phone's clock: Microsoft refuses a token made with a clock that is off, and says its own time. */
    @Volatile private var edgeSkew = 0L

    suspend fun speak(choice: String, text: String, language: String?, emit: suspend (ByteArray) -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        when (voiceKind(choice)) {
            VoiceKind.EDGE -> {
                val mp3 = edgeMp3(voiceIdOf(choice), clean)
                val pcm = withContext(Dispatchers.Default) { decodeToPcm24k(mp3) }
                for (o in pcm.indices step 9_600) emit(pcm.copyOfRange(o, minOf(pcm.size, o + 9_600)))
            }
            VoiceKind.ELEVEN -> eleven(voiceIdOf(choice), clean, language, emit)
            VoiceKind.GEMINI -> throw VoiceException("Voix Gemini : rien à lire ici.")
        }
    }

    /** The whole sentence as MP3 from Microsoft Edge's read-aloud service; one retry when the token was refused for the clock. */
    private suspend fun edgeMp3(voice: String, text: String): ByteArray {
        repeat(2) { attempt ->
            when (val r = edgeOnce(voice, text)) {
                is EdgeResult.Audio -> return r.mp3
                is EdgeResult.Refused -> {
                    val server = r.serverDate
                    if (attempt == 0 && server != null) edgeSkew = server - System.currentTimeMillis() / 1000 else
                        throw VoiceException("La voix Microsoft Edge a été refusée (HTTP ${r.code}). Le service a peut-être changé : reprenez la voix Gemini.")
                }
            }
        }
        throw VoiceException("La voix Microsoft Edge ne répond pas.")
    }

    private sealed interface EdgeResult {
        class Audio(val mp3: ByteArray) : EdgeResult
        class Refused(val code: Int, val serverDate: Long?) : EdgeResult
    }

    private suspend fun edgeOnce(voice: String, text: String): EdgeResult = withTimeout(30_000) {
        val now = System.currentTimeMillis() / 1000 + edgeSkew
        val request = Request.Builder().url(edgeUrl(now, UUID.randomUUID().toString().replace("-", ""))).apply {
            edgeHeaders(UUID.randomUUID().toString().replace("-", "").uppercase()).forEach { (k, v) -> header(k, v) }
        }.build()
        val audio = ByteArrayOutputStream()
        val done = CompletableDeferred<EdgeResult>()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(edgeConfigMessage(now))
                webSocket.send(edgeSsmlMessage(UUID.randomUUID().toString().replace("-", ""), now, voice, text))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (edgePath(text) == "turn.end") {
                    done.complete(if (audio.size() > 0) EdgeResult.Audio(audio.toByteArray()) else EdgeResult.Refused(0, null))
                    webSocket.close(1000, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                edgeAudio(bytes.toByteArray())?.let { synchronized(audio) { audio.write(it) } }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null) {
                    val date = response.header("Date")?.let { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull() }
                    done.complete(EdgeResult.Refused(response.code, date))
                } else {
                    done.completeExceptionally(VoiceException("La voix Microsoft Edge est injoignable (connexion ?)."))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(if (audio.size() > 0) EdgeResult.Audio(audio.toByteArray()) else EdgeResult.Refused(code, null))
            }
        })
        try {
            done.await()
        } finally {
            socket.cancel()
        }
    }

    private suspend fun eleven(voiceId: String, text: String, language: String?, emit: suspend (ByteArray) -> Unit) {
        val key = elevenKey()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw VoiceException("Aucune clé ElevenLabs : collez-la dans Paramètres > Voix, ou choisissez une autre voix.")
        val request = Request.Builder()
            .url("$ELEVEN_API/text-to-speech/$voiceId/stream?output_format=pcm_24000")
            .header("xi-api-key", key)
            .post(elevenRequest(text, language?.substringBefore('-')?.lowercase()).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        val response = withContext(Dispatchers.IO) {
            try {
                call.execute()
            } catch (e: IOException) {
                throw VoiceException("ElevenLabs est injoignable (connexion ?).")
            }
        }
        response.use { r ->
            if (!r.isSuccessful) throw VoiceException(elevenError(r.code))
            val source = r.body?.byteStream() ?: return
            val buffer = ByteArray(9_600)
            var carry: Byte? = null
            while (true) {
                val n = withContext(Dispatchers.IO) { source.read(buffer) }
                if (n <= 0) break
                // whole 16-bit samples only: an odd byte waits for the next read
                val bytes = (carry?.let { byteArrayOf(it) } ?: ByteArray(0)) + buffer.copyOf(n)
                carry = if (bytes.size % 2 == 1) bytes.last() else null
                emit(if (carry != null) bytes.copyOf(bytes.size - 1) else bytes)
            }
        }
    }

    /** The user's ElevenLabs voices, for the settings. */
    suspend fun elevenVoices(): List<OnlineVoice> = withContext(Dispatchers.IO) {
        val key = elevenKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
        val request = Request.Builder().url("$ELEVEN_API/voices").header("xi-api-key", key).build()
        try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw VoiceException(elevenError(r.code))
                parseElevenVoices(Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject)
            }
        } catch (e: IOException) {
            throw VoiceException("ElevenLabs est injoignable (connexion ?).")
        }
    }
}

/** Decodes an MP3 (or any audio Android reads) to 16-bit mono PCM at 24 kHz. */
internal fun decodeToPcm24k(encoded: ByteArray): ByteArray {
    val extractor = MediaExtractor()
    extractor.setDataSource(object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= encoded.size) return -1
            val n = minOf(size.toLong(), encoded.size - position).toInt()
            System.arraycopy(encoded, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = encoded.size.toLong()
        override fun close() {}
    })
    try {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw VoiceException("Son illisible.")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ByteArrayOutputStream()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(chunk)
                        out.write(chunk)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return toMono24k(out.toByteArray(), rate, channels)
    } finally {
        extractor.release()
    }
}
