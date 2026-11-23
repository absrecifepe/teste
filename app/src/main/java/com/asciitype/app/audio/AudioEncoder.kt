package com.asciitype.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

/** Áudio já codificado em AAC, pronto para ser gravado no MP4 junto com o vídeo. */
class EncodedAudio(val format: MediaFormat, val samples: List<Sample>) {
    class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
}

object AudioEncoder {

    private const val TIMEOUT_US = 10_000L

    /** Codifica PCM 16 bits mono em AAC-LC. */
    suspend fun encode(pcm: ShortArray, rate: Int = AudioSynth.RATE): EncodedAudio {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = ArrayList<EncodedAudio.Sample>()
        var outFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val count = minOf(buf.remaining() / 2, pcm.size - pos)
                        val pts = pos * 1_000_000L / rate
                        if (count <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, pos, count)
                            codec.queueInputBuffer(i, 0, count * 2, pts, 0)
                            pos += count
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    o >= 0 -> {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) {
                            val out = codec.getOutputBuffer(o)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            out.get(bytes)
                            samples += EncodedAudio.Sample(bytes, info.presentationTimeUs, info.flags)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return EncodedAudio(outFormat ?: error("Codificador de áudio não informou o formato"), samples)
    }
}
