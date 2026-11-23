package com.asciitype.app.audio

import androidx.annotation.StringRes
import com.asciitype.app.R
import com.asciitype.app.ascii.Timeline
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Estilos da música gerada pelo app. Cada vídeo sorteia uma variação dentro do estilo. */
enum class MusicStyle(@StringRes val label: Int) {
    CALM(R.string.music_calm),
    UPBEAT(R.string.music_upbeat),
    LOFI(R.string.music_lofi),
    SUSPENSE(R.string.music_suspense),
}

/** De onde vem a música do vídeo. */
sealed interface MusicSource {
    data object None : MusicSource
    class Generated(val style: MusicStyle, val seed: Long) : MusicSource
    /** Música do usuário, já decodificada em PCM mono a [AudioSynth.RATE]. */
    class Custom(val pcm: FloatArray) : MusicSource
}

/**
 * Gera a trilha sonora do vídeo no próprio aparelho: cliques de teclado sincronizados com a
 * digitação e uma música (gerada aqui, sem direitos autorais, ou escolhida pelo usuário) que
 * entra quando o zoom começa a se afastar.
 */
object AudioSynth {
    const val RATE = 44_100

    fun render(timeline: Timeline, typing: Boolean, music: MusicSource): ShortArray {
        val n = (timeline.totalFrames.toLong() * RATE / timeline.fps).toInt()
        val buf = FloatArray(n)
        val musicStart = timeline.introEndFrame.toFloat() / timeline.fps
        val hasMusic = music !is MusicSource.None
        if (typing) addTyping(buf, timeline, fadeAt = if (hasMusic) musicStart else null)
        when (music) {
            is MusicSource.Generated -> addMusic(buf, musicStart, MusicSpec.create(music.style, music.seed), 0.65f)
            is MusicSource.Custom -> addCustom(buf, musicStart, music.pcm)
            MusicSource.None -> Unit
        }
        // Limitador suave para nunca distorcer.
        return ShortArray(n) { (tanh(buf[it] * 1.1f) * 0.9f * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Duração (s) que a música precisa ter para cobrir o vídeo a partir do afastamento do zoom. */
    fun musicSecondsNeeded(timeline: Timeline): Float =
        (timeline.totalFrames - timeline.introEndFrame).toFloat() / timeline.fps + 0.5f

    // ------------------------------------------------------------------ teclado

    private fun addTyping(buf: FloatArray, tl: Timeline, fadeAt: Float?) {
        val rnd = Random(System.nanoTime())
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
            val steps = min(d, 4)
            for (k in 0 until steps) {
                val t = t0 + k.toFloat() / (steps * tl.fps)
                if (t - lastClick < minGap) continue
                lastClick = t
                // Com música: o teclado some durante o afastamento do zoom.
                val fade = when {
                    fadeAt == null -> if (t > endTyping - 1f) (endTyping - t).coerceAtLeast(0f) else 1f
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
            s += sin(TAU * thock * t) * 0.6f * exp(-t / 0.012f)
            val tr = (i - releaseAt) / RATE
            if (tr >= 0) s += hp * 0.18f * exp(-tr / 0.003f)
            out[i] = s
        }
        return out
    }

    // ------------------------------------------------------------------ música do usuário

    private fun addCustom(buf: FloatArray, start: Float, pcm: FloatArray) {
        val startI = (start * RATE).toInt().coerceIn(0, buf.size)
        val len = min(pcm.size, buf.size - startI)
        if (len <= 0) return
        val fadeIn = 1.5f * RATE
        val fadeOut = 1.2f * RATE
        for (k in 0 until len) {
            val a = (k / fadeIn).coerceAtMost(1f) * ((len - k) / fadeOut).coerceAtMost(1f)
            buf[startI + k] += pcm[k] * 0.75f * a
        }
    }

    // ------------------------------------------------------------------ música gerada

    /** Todas as escolhas de uma música, sorteadas a partir do estilo e de uma semente. */
    private class MusicSpec(
        val bpm: Float,
        val chords: List<IntArray>, // notas do pad (MIDI) de cada compasso
        val bass: IntArray,         // nota do baixo de cada compasso
        val arp: IntArray,          // índices nas notas do acorde (-1 = pausa; >= tamanho = oitava acima)
        val arpPerBeat: Int,
        val arpOctave: Int,
        val swing: Float,           // 0,5 = reto; > 0,5 = balançado
        val harmonics: Int,         // brilho do timbre
        val padGain: Float,
        val arpGain: Float,
        val kick: FloatArray,       // posições (em tempos) do bumbo no compasso
        val snare: FloatArray,
        val hatPerBeat: Int,
        val hatGain: Float,
        val bassPerBeat: Int,
        val bassOffbeat: Boolean,
        val crackle: Boolean,
        val lowpass: Float,         // 1 = sem filtro; menor = mais abafado
        val echo: Float,
    ) {
        companion object {
            // (grau em semitons, qualidade: 0 maior, 1 menor)
            private val MAJOR = listOf(
                listOf(9 to 1, 5 to 0, 0 to 0, 7 to 0),
                listOf(0 to 0, 7 to 0, 9 to 1, 5 to 0),
                listOf(0 to 0, 9 to 1, 5 to 0, 7 to 0),
                listOf(5 to 0, 7 to 0, 4 to 1, 9 to 1),
                listOf(2 to 1, 7 to 0, 0 to 0, 9 to 1),
                listOf(0 to 0, 5 to 0, 9 to 1, 7 to 0),
            )
            private val MINOR = listOf(
                listOf(0 to 1, 8 to 0, 3 to 0, 10 to 0),
                listOf(0 to 1, 5 to 1, 8 to 0, 7 to 0),
                listOf(0 to 1, 10 to 0, 8 to 0, 7 to 0),
                listOf(0 to 1, 3 to 0, 5 to 1, 7 to 0),
            )

            fun create(style: MusicStyle, seed: Long): MusicSpec {
                val r = Random(seed)
                val key = 55 + r.nextInt(-5, 7) // tonalidade sorteada
                val prog = (if (style == MusicStyle.SUSPENSE) MINOR else MAJOR).random(r)
                val sevenths = style == MusicStyle.LOFI || (style == MusicStyle.CALM && r.nextBoolean())
                val chords = prog.map { (deg, q) -> voicing(key + deg, q, sevenths) }
                val bass = IntArray(4) { i -> fold(key + prog[i].first, 33, 45) }
                return when (style) {
                    MusicStyle.CALM -> MusicSpec(
                        bpm = r.nextInt(84, 101).toFloat(), chords = chords, bass = bass,
                        arp = listOf(
                            intArrayOf(0, 1, 2, 3, 2, 1, 0, 1), intArrayOf(0, 2, 1, 3, 0, 2, 1, 3),
                            intArrayOf(0, 1, 2, 1, 3, 2, 1, 2), intArrayOf(2, 1, 0, 1, 2, 3, 2, 1),
                        ).random(r),
                        arpPerBeat = 2, arpOctave = 12, swing = 0.5f, harmonics = r.nextInt(3, 5),
                        padGain = 0.05f, arpGain = 0.11f,
                        kick = floatArrayOf(0f, 2f), snare = floatArrayOf(), hatPerBeat = 1, hatGain = 0.035f,
                        bassPerBeat = 1, bassOffbeat = false, crackle = false, lowpass = 1f, echo = 0.35f,
                    )
                    MusicStyle.UPBEAT -> MusicSpec(
                        bpm = r.nextInt(116, 129).toFloat(), chords = chords, bass = bass,
                        arp = listOf(
                            intArrayOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3),
                            intArrayOf(0, 2, 3, 2, 1, 2, 3, 2, 0, 2, 3, 2, 1, 2, 3, 2),
                            intArrayOf(3, 2, 1, 0, 3, 2, 1, 0, 3, 2, 1, 2, 3, 2, 1, 2),
                        ).random(r),
                        arpPerBeat = 4, arpOctave = 12, swing = 0.5f, harmonics = r.nextInt(4, 6),
                        padGain = 0.035f, arpGain = 0.075f,
                        kick = floatArrayOf(0f, 1f, 2f, 3f), snare = floatArrayOf(1f, 3f), hatPerBeat = 2, hatGain = 0.04f,
                        bassPerBeat = 2, bassOffbeat = true, crackle = false, lowpass = 1f, echo = 0.25f,
                    )
                    MusicStyle.LOFI -> MusicSpec(
                        bpm = r.nextInt(72, 87).toFloat(), chords = chords, bass = bass,
                        arp = listOf(
                            intArrayOf(0, -1, 2, 1, -1, 3, -1, 2), intArrayOf(3, -1, 2, -1, 1, 2, -1, 0),
                            intArrayOf(0, 2, -1, 3, -1, 2, 1, -1),
                        ).random(r),
                        arpPerBeat = 2, arpOctave = 12, swing = 0.62f, harmonics = 2,
                        padGain = 0.055f, arpGain = 0.12f,
                        kick = floatArrayOf(0f, 2.5f), snare = floatArrayOf(1f, 3f), hatPerBeat = 2, hatGain = 0.02f,
                        bassPerBeat = 1, bassOffbeat = false, crackle = true, lowpass = 0.22f, echo = 0.3f,
                    )
                    MusicStyle.SUSPENSE -> MusicSpec(
                        bpm = r.nextInt(64, 77).toFloat(), chords = chords, bass = bass,
                        arp = listOf(intArrayOf(0, 2, 1, 2), intArrayOf(0, 1, 2, 1), intArrayOf(2, 1, 0, -1)).random(r),
                        arpPerBeat = 1, arpOctave = 0, swing = 0.5f, harmonics = 2,
                        padGain = 0.07f, arpGain = 0.13f,
                        kick = floatArrayOf(0f, 0.35f), snare = floatArrayOf(), hatPerBeat = 0, hatGain = 0f,
                        bassPerBeat = 0, bassOffbeat = false, crackle = false, lowpass = 0.35f, echo = 0.45f,
                    )
                }
            }

            private fun voicing(root: Int, quality: Int, seventh: Boolean): IntArray {
                val iv = mutableListOf(0, if (quality == 1) 3 else 4, 7)
                if (seventh) iv += if (quality == 1) 10 else 11
                return iv.map { fold(root + it, 55, 67) }.sorted().toIntArray()
            }

            /** Leva a nota para dentro de [lo, hi] trocando de oitava. */
            private fun fold(note: Int, lo: Int, hi: Int): Int {
                var n = note
                while (n < lo) n += 12
                while (n > hi) n -= 12
                return n
            }
        }
    }

    private fun freq(midi: Int) = 440f * 2f.pow((midi - 69) / 12f)

    private fun addMusic(buf: FloatArray, start: Float, spec: MusicSpec, gain: Float) {
        val n = buf.size
        val startI = (start * RATE).toInt().coerceIn(0, n)
        if (n - startI < RATE) return
        val music = FloatArray(n)
        val arp = FloatArray(n)
        val rnd = Random(spec.hashCode().toLong())
        val beat = 60f / spec.bpm
        val bar = beat * 4
        val end = n.toFloat() / RATE
        var b = 0
        var t = start
        while (t < end) {
            val c = b % 4
            val chord = spec.chords[c]
            for (m in chord) pad(music, freq(m), t, bar + 0.3f, spec.padGain, spec.harmonics)
            if (spec.bassPerBeat == 0) {
                pad(music, freq(spec.bass[c]), t, bar + 0.3f, 0.09f, 2) // grave contínuo (suspense)
            }
            for (k in 0 until 4) {
                val tb = t + k * beat
                for (s in 0 until spec.bassPerBeat) {
                    val off = if (spec.bassOffbeat) beat / 2 else s * beat / spec.bassPerBeat
                    if (spec.bassOffbeat && s > 0) continue
                    bass(music, freq(spec.bass[c]), tb + off, 0.16f)
                }
                for (h in 0 until spec.hatPerBeat) {
                    val pos = if (spec.hatPerBeat == 1) 0.5f else swingPos(h, spec.hatPerBeat, spec.swing)
                    hat(music, tb + pos * beat, spec.hatGain, rnd)
                }
            }
            for (k in spec.kick) kick(music, t + k * beat, 0.32f)
            for (k in spec.snare) snare(music, t + k * beat, 0.12f, rnd)
            val steps = spec.arpPerBeat * 4
            for (e in 0 until steps) {
                val idx = spec.arp[e % spec.arp.size]
                if (idx < 0) continue
                val note = if (idx >= chord.size) chord[idx - chord.size] + 12 else chord[idx]
                val beatIdx = e / spec.arpPerBeat
                val pos = swingPos(e % spec.arpPerBeat, spec.arpPerBeat, spec.swing)
                pluck(arp, freq(note + spec.arpOctave), t + (beatIdx + pos) * beat, spec.arpGain, spec.harmonics)
            }
            b++
            t += bar
        }
        // Eco no arpejo.
        val delay = (beat * 0.75f * RATE).toInt()
        for (i in delay until n) arp[i] += arp[i - delay] * spec.echo
        for (i in 0 until n) music[i] += arp[i]
        // Filtro passa-baixas (som abafado do lo-fi/suspense).
        if (spec.lowpass < 1f) {
            var y = 0f
            for (i in 0 until n) { y += spec.lowpass * (music[i] - y); music[i] = y }
        }
        if (spec.crackle) {
            for (i in 0 until n) {
                if (rnd.nextFloat() < 0.0004f) music[i] += (rnd.nextFloat() - 0.5f) * 0.25f
                music[i] += (rnd.nextFloat() - 0.5f) * 0.006f
            }
        }
        // Entrada (1,5 s) e saída (1,2 s) da música.
        val fadeIn = 1.5f * RATE
        val fadeOut = 1.2f * RATE
        for (i in startI until n) {
            val a = ((i - startI) / fadeIn).coerceAtMost(1f) * ((n - i) / fadeOut).coerceAtMost(1f)
            buf[i] += music[i] * gain * a
        }
    }

    /** Posição (fração do tempo) da subdivisão [s] de [per], aplicando o balanço (swing). */
    private fun swingPos(s: Int, per: Int, swing: Float): Float =
        if (per == 2 && s == 1) swing else s.toFloat() / per

    private const val TAU = (2 * PI).toFloat()

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

    private fun pad(buf: FloatArray, f: Float, start: Float, dur: Float, gain: Float, harmonics: Int) =
        note(buf, start, dur) { t ->
            val env = (t / 0.4f).coerceAtMost(1f) * ((dur - t) / 0.5f).coerceIn(0f, 1f)
            var s = 0f
            for (h in 1..harmonics) {
                s += sin(TAU * f * h * t) / h
                s += sin(TAU * f * 1.004f * h * t) / h // leve desafinação = som "largo"
            }
            s * 0.5f * env * gain
        }

    private fun pluck(buf: FloatArray, f: Float, start: Float, gain: Float, harmonics: Int) =
        note(buf, start, 0.4f) { t ->
            val w = TAU * f * t
            var s = sin(w)
            if (harmonics >= 3) s += 0.3f * sin(2 * w)
            if (harmonics >= 5) s += 0.12f * sin(3 * w)
            s * exp(-t / 0.12f) * (t / 0.004f).coerceAtMost(1f) * gain
        }

    private fun bass(buf: FloatArray, f: Float, start: Float, gain: Float) =
        note(buf, start, 0.55f) { t ->
            val w = TAU * f * t
            (sin(w) + 0.2f * sin(2 * w)) * exp(-t / 0.25f) * (t / 0.005f).coerceAtMost(1f) * gain
        }

    private fun kick(buf: FloatArray, start: Float, gain: Float) {
        var phase = 0f
        note(buf, start, 0.3f) { t ->
            val f = 45f + 75f * exp(-t / 0.03f)
            phase += TAU * f / RATE
            sin(phase) * exp(-t / 0.12f) * gain
        }
    }

    private fun snare(buf: FloatArray, start: Float, gain: Float, rnd: Random) {
        note(buf, start, 0.2f) { t ->
            val noise = rnd.nextFloat() * 2f - 1f
            (noise * exp(-t / 0.06f) + 0.5f * sin(TAU * 190f * t) * exp(-t / 0.03f)) * gain
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
