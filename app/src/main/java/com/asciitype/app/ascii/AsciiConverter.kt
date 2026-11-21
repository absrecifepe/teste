package com.asciitype.app.ascii

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.asciitype.app.R
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** Conjuntos de caracteres, ordenados do "vazio" para o "denso". */
enum class Charset(@StringRes val label: Int, val ramp: String, val binary: Boolean = false) {
    CLASSIC(R.string.charset_classic, " .:-=+*#%@"),
    DETAILED(R.string.charset_detailed, " .'`^\",:;Il!i><~+_-?][}{1)(|/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$"),
    BLOCKS(R.string.charset_blocks, " ░▒▓█"),
    BINARY(R.string.charset_binary, " 01", binary = true),
}

/**
 * Resultado da conversão: uma grade [cols] x [rows] de caracteres.
 * [ink] é a intensidade (0..1) de cada célula já no sentido "mais tinta = mais denso".
 * [rgb] é a cor média original de cada célula.
 */
class AsciiArt(
    val cols: Int,
    val rows: Int,
    val chars: CharArray,
    val ink: FloatArray,
    val rgb: IntArray,
)

object AsciiConverter {

    fun convert(
        src: Bitmap,
        cols: Int,
        rows: Int,
        charset: Charset,
        denseIsDark: Boolean,
        seed: Long = 7L,
    ): AsciiArt {
        // Amostra 2x2 pixels por célula para suavizar.
        val sw = cols * 2
        val sh = rows * 2
        val small = downscale(src, sw, sh)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        val n = cols * rows
        val lum = FloatArray(n)
        val rgb = IntArray(n)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = px[(row * 2 + dy) * sw + col * 2 + dx]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                r /= 4; g /= 4; b /= 4
                val i = row * cols + col
                rgb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                lum[i] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            }
        }

        // Auto-contraste: estica entre os percentis 2% e 98%.
        val sorted = lum.copyOf().also { it.sort() }
        val lo = sorted[(n * 0.02f).toInt().coerceIn(0, n - 1)]
        val hi = sorted[(n * 0.98f).toInt().coerceIn(0, n - 1)]
        val range = (hi - lo).coerceAtLeast(0.05f)

        val ramp = charset.ramp
        val last = ramp.length - 1
        val rnd = Random(seed)
        val chars = CharArray(n)
        val ink = FloatArray(n)
        for (i in 0 until n) {
            var v = ((lum[i] - lo) / range).coerceIn(0f, 1f)
            v = v.pow(0.9f)
            if (denseIsDark) v = 1f - v
            ink[i] = v
            val idx = (v * last).roundToInt()
            chars[i] = when {
                idx == 0 -> ' '
                charset.binary -> if (rnd.nextBoolean()) '1' else '0'
                else -> ramp[idx]
            }
        }
        return AsciiArt(cols, rows, chars, ink, rgb)
    }

    /** Reduz em etapas de 1/2 para evitar serrilhado, e então para o tamanho final. */
    private fun downscale(src: Bitmap, w: Int, h: Int): Bitmap {
        var b = src
        while (b.width / 2 >= w * 2 && b.height / 2 >= h * 2) {
            val next = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== src) b.recycle()
            b = next
        }
        val out = Bitmap.createScaledBitmap(b, w, h, true)
        if (b !== src && b !== out) b.recycle()
        return out
    }
}
