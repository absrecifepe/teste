package com.asciitype.app.ascii

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.asciitype.app.audio.EncodedAudio
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Codifica a animação em MP4 (H.264) usando MediaCodec + MediaMuxer, sem bibliotecas externas.
 * Os quadros são desenhados em Bitmap, convertidos para YUV 4:2:0 e enviados ao codificador
 * com timestamps exatos.
 */
object VideoExporter {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    /** Larguras tentadas, da maior para a menor, até achar uma suportada pelo codificador. */
    private val CANDIDATE_WIDTHS = intArrayOf(1080, 720, 640, 480)

    fun chooseLayout(imgW: Int, imgH: Int, cols: Int, extraRows: Int = 0): FrameLayout {
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MIME).videoCapabilities }.getOrNull() }
        for (w in CANDIDATE_WIDTHS) {
            val layout = FrameLayout.compute(imgW, imgH, cols, w, w * 16 / 9, extraRows)
            if (caps.isEmpty() || caps.any { it.isSizeSupported(layout.width, layout.height) }) return layout
        }
        return FrameLayout.compute(imgW, imgH, cols, 480, 854, extraRows)
    }

    suspend fun export(
        renderer: AsciiRenderer,
        layout: FrameLayout,
        timeline: Timeline,
        out: File,
        audio: EncodedAudio?,
        onProgress: (Float) -> Unit,
    ) {
        val w = layout.width
        val h = layout.height
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // Texto tem bordas nítidas: usa um bitrate generoso.
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * timeline.fps * 0.25f).toInt().coerceIn(2_000_000, 16_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, timeline.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }

        val codec = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val argb = IntArray(w * h)
        val yRow = ByteArray(w)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val drainer = Drainer(codec, muxer, audio)

            val total = timeline.totalFrames
            for (f in 0 until total) {
                currentCoroutineContext().ensureActive()
                renderer.renderFrame(timeline.revealedAt(f), timeline.cursorAt(f), timeline.zoomAt(f), frame)
                frame.getPixels(argb, 0, w, 0, 0, w, h)

                val inIndex = dequeueInput(codec, drainer)
                val image = codec.getInputImage(inIndex) ?: error("Codificador não forneceu Image")
                fillYuv(image, argb, w, h, yRow)
                codec.queueInputBuffer(inIndex, 0, w * h * 3 / 2, timeline.ptsUs(f), 0)
                drainer.drain(endOfStream = false)
                onProgress((f + 1f) / total)
            }
            val inIndex = dequeueInput(codec, drainer)
            codec.queueInputBuffer(inIndex, 0, 0, timeline.ptsUs(total), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainer.drain(endOfStream = true)
            drainer.writeAudioUpTo(Long.MAX_VALUE)
        } finally {
            frame.recycle()
            runCatching { codec.stop() }
            codec.release()
            muxer?.let { m -> runCatching { m.stop() }; m.release() }
        }
    }

    private suspend fun dequeueInput(codec: MediaCodec, drainer: Drainer): Int {
        while (true) {
            currentCoroutineContext().ensureActive()
            val i = codec.dequeueInputBuffer(TIMEOUT_US)
            if (i >= 0) return i
            drainer.drain(endOfStream = false)
        }
    }

    private class Drainer(
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
        private val audio: EncodedAudio?,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var audioTrack = -1
        private var audioPos = 0
        private val audioInfo = MediaCodec.BufferInfo()

        /** Grava as amostras de áudio até [ptsUs], intercalando com o vídeo. */
        fun writeAudioUpTo(ptsUs: Long) {
            val a = audio ?: return
            if (audioTrack < 0) return
            while (audioPos < a.samples.size && a.samples[audioPos].ptsUs <= ptsUs) {
                val s = a.samples[audioPos++]
                audioInfo.set(0, s.data.size, s.ptsUs, s.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), audioInfo)
            }
        }

        fun drain(endOfStream: Boolean) {
            var idleTries = 0
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++idleTries > 500) error("Codificador não finalizou")
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(track < 0) { "Formato mudou duas vezes" }
                        track = muxer.addTrack(codec.outputFormat)
                        if (audio != null) audioTrack = muxer.addTrack(audio.format)
                        muxer.start()
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            writeAudioUpTo(info.presentationTimeUs)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    /** ARGB -> YUV 4:2:0 (BT.709, faixa limitada), respeitando strides de qualquer layout de Image. */
    private fun fillYuv(image: Image, argb: IntArray, w: Int, h: Int, yRow: ByteArray) {
        val planes = image.planes
        val yP = planes[0]; val uP = planes[1]; val vP = planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        val yStride = yP.rowStride; val yPix = yP.pixelStride
        val uStride = uP.rowStride; val uPix = uP.pixelStride
        val vStride = vP.rowStride; val vPix = vP.pixelStride

        for (j in 0 until h) {
            val off = j * w
            if (yPix == 1) {
                for (i in 0 until w) yRow[i] = luma(argb[off + i]).toByte()
                yBuf.position(j * yStride)
                yBuf.put(yRow, 0, w)
            } else {
                for (i in 0 until w) yBuf.put(j * yStride + i * yPix, luma(argb[off + i]).toByte())
            }
        }

        for (j in 0 until h / 2) {
            val r0 = 2 * j * w
            val r1 = r0 + w
            for (i in 0 until w / 2) {
                val x = 2 * i
                val a = argb[r0 + x]; val b = argb[r0 + x + 1]; val c = argb[r1 + x]; val d = argb[r1 + x + 1]
                val r = ((a shr 16 and 0xFF) + (b shr 16 and 0xFF) + (c shr 16 and 0xFF) + (d shr 16 and 0xFF)) shr 2
                val g = ((a shr 8 and 0xFF) + (b shr 8 and 0xFF) + (c shr 8 and 0xFF) + (d shr 8 and 0xFF)) shr 2
                val bl = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF)) shr 2
                val u = ((-26 * r - 87 * g + 112 * bl + 128) shr 8) + 128
                val v = ((112 * r - 102 * g - 10 * bl + 128) shr 8) + 128
                uBuf.put(j * uStride + i * uPix, u.coerceIn(16, 240).toByte())
                vBuf.put(j * vStride + i * vPix, v.coerceIn(16, 240).toByte())
            }
        }
    }

    private fun luma(c: Int): Int {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        return (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16).coerceIn(16, 235)
    }
}
