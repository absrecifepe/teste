package com.asciitype.app.audio

import com.asciitype.app.ascii.Timeline
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Gera a trilha sonora do vídeo inteiramente no aparelho (sem arquivos de áudio nem direitos
 * autorais): cliques de teclado sincronizados com a digitação e uma música eletrônica suave
 * que entra quando o zoom começa a se afastar.
 */
object AudioSynth {
    const val RATE = 44_100

    fun render(timeline: Timeline, typing: Boolean, music: Boolean): ShortArray {
        val n = (timeline.totalFrames.toLong() * RATE / timeline.fps).toInt()
        val buf = FloatArray(n)
        val musicStart = timeline.introEndFrame.toFloat() / timeline.fps
        if (typing) addTyping(buf, timeline, fadeAt = if (music) musicStart else null)
        if (music) addMusic(buf, musicStart)
        // Limitador suave para nunca distorcer.
        return ShortArray(n) { (tanh(buf[it] * 1.1f) * 0.9f * Short.MAX_VALUE).toInt().toShort() }
    }

    // ------------------------------------------------------------------ teclado

    private fun addTyping(buf: FloatArray, tl: Timeline, fadeAt: Float?) {
        val rnd = Random(11)
        val clicks = List(8) { makeClick(rnd) }
        val minGap = 1f / 18f // no máximo ~18 cliques/s, mesmo quando a digitação acelera
        var lastClick = -1f
        var prev = 0
        val endTyping = tl.mainEndFrame.toFloat() / tl.fps
        for (f in 0 until tl.totalFrames) {
            val now = tl.revealedAt(f)
            val d = now - prev
            prev = now
            if (d <= 0) continue
            val t0 = f.toFloat() / tl.fps
            for (k in 0 until min(d, 4)) {
                val t = t0 + k.toFloat() / (min(d, 4) * tl.fps)
                if (t - lastClick < minGap) continue
                lastClick = t
                // Com música: o teclado some durante o afastamento do zoom.
                val fade = when {
                    fadeAt == null -> if (t > endTyping - 1f) ((endTyping - t).coerceAtLeast(0f)) else 1f
                    t <= fadeAt -> 1f
                    else -> (1f - (t - fadeAt) / 3f).coerceIn(0f, 1f)
                }
                if (fade <= 0f) continue
                val gain = 0.5f * fade * (0.7f + 0.3f * rnd.nextFloat())
                mix(buf, clicks[rnd.nextInt(clicks.size)], (t * RATE).toInt(), gain)
            }
        }
    }

    /** Um clique de tecla: estalo agudo + "toc" grave + leve som de soltura da tecla. */
    private fun makeClick(rnd: Random): FloatArray {
        val len = (0.06f * RATE).toInt()
        val out = FloatArray(len)
        val thock = 170f + rnd.nextFloat() * 110f
        val releaseAt = (0.022f + rnd.nextFloat() * 0.01f) * RATE
        var last = 0f
        for (i in 0 until len) {
            val t = i.toFloat() / RATE
            val noise = rnd.nextFloat() * 2f - 1f
            val hp = noise - last // passa-altas simples
            last = noise
            var s = hp * 0.55f * exp(-t / 0.0035f)
            s += sin(2f * PI.toFloat() * thock * t) * 0.6f * exp(-t / 0.012f)
            val tr = (i - releaseAt) / RATE
            if (tr >= 0) s += hp * 0.18f * exp(-tr / 0.003f)
            out[i] = s
        }
        return out
    }

    // ------------------------------------------------------------------ música

    private const val BPM = 100f
    private const val BEAT = 60f / BPM
    private const val BAR = BEAT * 4

    // Progressão Am - F - C - G (vozes do pad e baixo, em notas MIDI).
    private val PADS = arrayOf(intArrayOf(57, 60, 64), intArrayOf(57, 60, 65), intArrayOf(55, 60, 64), intArrayOf(55, 59, 62))
    private val BASS = intArrayOf(45, 41, 48, 43)
    private val ARP = intArrayOf(0, 1, 2, 3, 2, 1, 0, 1) // índices nas notas do acorde (3 = 1ª nota oitava acima)

    private fun freq(midi: Int) = 440f * 2f.pow((midi - 69) / 12f)

    private fun addMusic(buf: FloatArray, start: Float) {
        val n = buf.size
        val startI = (start * RATE).toInt().coerceIn(0, n)
        if (n - startI < RATE) return
        val music = FloatArray(n)
        val arp = FloatArray(n)
        val rnd = Random(5)
        val end = n.toFloat() / RATE
        var bar = 0
        var t = start
        while (t < end) {
            val c = bar % 4
            for (m in PADS[c]) pad(music, freq(m), t, BAR + 0.3f, 0.05f)
            for (b in 0 until 4) {
                val tb = t + b * BEAT
                bass(music, freq(BASS[c]), tb, 0.16f)
                if (b % 2 == 0) kick(music, tb, 0.32f)
                hat(music, tb + BEAT / 2, 0.035f, rnd)
            }
            for (e in 0 until 8) {
                val idx = ARP[e]
                val note = if (idx == 3) PADS[c][0] + 12 else PADS[c][idx]
                pluck(arp, freq(note + 12), t + e * BEAT / 2, 0.11f)
            }
            bar++
            t += BAR
        }
        // Eco no arpejo.
        val delay = (BEAT * 0.75f * RATE).toInt()
        for (i in delay until n) arp[i] += arp[i - delay] * 0.35f
        // Entrada (1,5 s) e saída (1,2 s) da música.
        val fadeIn = 1.5f * RATE
        val fadeOut = 1.2f * RATE
        for (i in startI until n) {
            val a = ((i - startI) / fadeIn).coerceAtMost(1f) * ((n - i) / fadeOut).coerceAtMost(1f)
            buf[i] += (music[i] + arp[i]) * 0.65f * a
        }
    }

    private inline fun note(buf: FloatArray, start: Float, dur: Float, sample: (Float) -> Float) {
        val i0 = (start * RATE).toInt()
        val len = (dur * RATE).toInt()
        for (k in 0 until len) {
            val i = i0 + k
            if (i < 0) continue
            if (i >= buf.size) break
            buf[i] += sample(k.toFloat() / RATE)
        }
    }

    private fun pad(buf: FloatArray, f: Float, start: Float, dur: Float, gain: Float) =
        note(buf, start, dur) { t ->
            val env = (t / 0.4f).coerceAtMost(1f) * ((dur - t) / 0.5f).coerceIn(0f, 1f)
            var s = 0f
            for (h in 1..4) {
                s += sin(2f * PI.toFloat() * f * h * t) / h
                s += sin(2f * PI.toFloat() * f * 1.004f * h * t) / h // leve desafinação = som "largo"
            }
            s * 0.5f * env * gain
        }

    private fun pluck(buf: FloatArray, f: Float, start: Float, gain: Float) =
        note(buf, start, 0.4f) { t ->
            val w = 2f * PI.toFloat() * f * t
            (sin(w) + 0.3f * sin(2 * w)) * exp(-t / 0.12f) * (t / 0.004f).coerceAtMost(1f) * gain
        }

    private fun bass(buf: FloatArray, f: Float, start: Float, gain: Float) =
        note(buf, start, 0.55f) { t ->
            val w = 2f * PI.toFloat() * f * t
            (sin(w) + 0.2f * sin(2 * w)) * exp(-t / 0.25f) * (t / 0.005f).coerceAtMost(1f) * gain
        }

    private fun kick(buf: FloatArray, start: Float, gain: Float) {
        var phase = 0f
        note(buf, start, 0.3f) { t ->
            val f = 45f + 75f * exp(-t / 0.03f)
            phase += 2f * PI.toFloat() * f / RATE
            sin(phase) * exp(-t / 0.12f) * gain
        }
    }

    private fun hat(buf: FloatArray, start: Float, gain: Float, rnd: Random) {
        var last = 0f
        note(buf, start, 0.06f) { t ->
            val n = rnd.nextFloat() * 2f - 1f
            val hp = n - last
            last = n
            hp * exp(-t / 0.015f) * gain
        }
    }

    private fun mix(buf: FloatArray, src: FloatArray, at: Int, gain: Float) {
        for (k in src.indices) {
            val i = at + k
            if (i >= buf.size) break
            if (i >= 0) buf[i] += src[k] * gain
        }
    }
}
