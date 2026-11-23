package com.asciitype.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

/** Lê um arquivo de música do usuário (MP3, M4A, OGG, WAV…) e converte para PCM mono. */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

    /** Duração do arquivo em segundos (0 se desconhecida). */
    fun durationSeconds(context: Context, uri: Uri): Float {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            val t = audioTrack(ex) ?: return 0f
            val f = ex.getTrackFormat(t)
            if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1_000_000f else 0f
        } finally {
            ex.release()
        }
    }

    /**
     * Decodifica [seconds] segundos a partir de [startSec] e devolve PCM mono em [outRate] Hz,
     * com amplitude entre -1 e 1.
     */
    suspend fun decodeMono(
        context: Context,
        uri: Uri,
        startSec: Float,
        seconds: Float,
        outRate: Int = AudioSynth.RATE,
    ): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = audioTrack(ex) ?: error("Arquivo sem áudio")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val startUs = (startSec * 1_000_000).toLong()
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var out = FloatArray((seconds * srcRate).toInt() + srcRate)
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    val need = (seconds * srcRate).toInt() + srcRate
                    if (out.size < need) out = out.copyOf(need)
                } else if (o >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= startUs) {
                        val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frames = if (isFloat) info.size / 4 / channels else info.size / 2 / channels
                        if (count + frames > out.size) out = out.copyOf(count + frames + srcRate)
                        if (isFloat) {
                            val fb = buf.asFloatBuffer()
                            for (k in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += fb.get()
                                out[count++] = s / channels
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            for (k in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += sb.get()
                                out[count++] = s / channels / 32768f
                            }
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                    val enough = count >= seconds * srcRate
                    if (enough || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return resample(out, count, srcRate, outRate)
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            ex.release()
        }
    }

    private fun audioTrack(ex: MediaExtractor): Int? =
        (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    /** Reamostragem linear simples. */
    private fun resample(src: FloatArray, count: Int, from: Int, to: Int): FloatArray {
        if (from == to) return src.copyOf(count)
        val n = (count.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return FloatArray(n) { i ->
            val p = i * ratio
            val k = p.toInt()
            val frac = (p - k).toFloat()
            val a = src[k.coerceAtMost(count - 1)]
            val b = src[(k + 1).coerceAtMost(count - 1)]
            a + (b - a) * frac
        }
    }
}
