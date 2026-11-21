package com.asciitype.app.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.StringRes
import com.asciitype.app.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Estilos de cor do vídeo. */
enum class ColorStyle(
    @StringRes val label: Int,
    val background: Int,
    val ink: Int,
    val cursor: Int,
    val denseIsDark: Boolean = false,
    val useOriginalColors: Boolean = false,
) {
    MATRIX(R.string.style_matrix, 0xFF050805.toInt(), 0xFF39FF6A.toInt(), 0xFF39FF6A.toInt()),
    TERMINAL(R.string.style_terminal, 0xFF0A0A0A.toInt(), 0xFFF2F2F2.toInt(), 0xFFF2F2F2.toInt()),
    COLOR(R.string.style_color, 0xFF000000.toInt(), Color.WHITE, Color.WHITE, useOriginalColors = true),
    AMBER(R.string.style_amber, 0xFF0D0700.toInt(), 0xFFFFB000.toInt(), 0xFFFFB000.toInt()),
    PAPER(R.string.style_paper, 0xFFF7F3EA.toInt(), 0xFF1A1A1A.toInt(), 0xFF1A1A1A.toInt(), denseIsDark = true),
}

/** Geometria do quadro: grade de caracteres + tamanho do vídeo em pixels. */
data class FrameLayout(
    val cols: Int,
    val rows: Int,
    val cellW: Float,
    val cellH: Float,
    val textSize: Float,
    val width: Int,
    val height: Int,
    val offsetX: Float,
    val offsetY: Float,
) {
    companion object {
        fun monoPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        /**
         * Calcula a grade para uma imagem [imgW]x[imgH] com [cols] colunas, cabendo
         * em [maxWidth]x[maxHeight]. Largura/altura finais são múltiplos de 16 (exigência de codecs).
         */
        fun compute(imgW: Int, imgH: Int, cols: Int, maxWidth: Int, maxHeight: Int): FrameLayout {
            val p = monoPaint().apply { textSize = 100f }
            val charW = p.measureText("M")
            val fm = p.fontMetrics
            val lineH = fm.descent - fm.ascent
            val ratio = lineH / charW // altura/largura de uma célula

            val rows = max(1, (cols * imgH.toFloat() / imgW / ratio).roundToInt())
            val marginCells = 2f
            var cellW = maxWidth / (cols + marginCells * 2)
            var cellH = cellW * ratio
            val needH = (rows + marginCells * 2 / ratio * 1f) * cellH
            if (needH > maxHeight) {
                cellH = maxHeight / (rows + marginCells * 2 / ratio)
                cellW = cellH / ratio
            }
            val contentW = cols * cellW
            val contentH = rows * cellH
            val margin = cellW * marginCells
            val width = roundUp16(contentW + margin * 2)
            val height = roundUp16(contentH + margin * 2)
            return FrameLayout(
                cols = cols,
                rows = rows,
                cellW = cellW,
                cellH = cellH,
                textSize = 100f * cellW / charW,
                width = width,
                height = height,
                offsetX = (width - contentW) / 2f,
                offsetY = (height - contentH) / 2f,
            )
        }

        private fun roundUp16(v: Float): Int = (ceil(v / 16f).toInt() * 16).coerceAtLeast(16)
    }
}

/**
 * Desenha a arte ASCII sendo "digitada". Mantém um bitmap base onde os caracteres já
 * digitados são acumulados, então cada quadro só desenha os caracteres novos.
 */
class AsciiRenderer(
    private val art: AsciiArt,
    private val layout: FrameLayout,
    private val style: ColorStyle,
) {
    /** Índices das células não vazias, em ordem de leitura (ordem de digitação). */
    private val order: IntArray = art.chars.indices.filter { art.chars[it] != ' ' }.toIntArray()
    val typedCount: Int get() = order.size

    private val paint = FrameLayout.monoPaint().apply { textSize = layout.textSize }
    private val cursorPaint = Paint().apply { color = style.cursor }
    private val baselineShift = -paint.fontMetrics.ascent

    private val base: Bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
    private val baseCanvas = Canvas(base)
    private var drawn = 0

    init {
        base.eraseColor(style.background)
    }

    private fun colorFor(i: Int): Int {
        val k = 0.35f + 0.65f * art.ink[i]
        val c = if (style.useOriginalColors) boost(art.rgb[i]) else style.ink
        // Mais "tinta" = mais próximo da cor da tinta; menos = mais próximo do fundo.
        return mix(style.background, c, k)
    }

    private fun drawRange(canvas: Canvas, from: Int, to: Int) {
        for (k in from until to) {
            val i = order[k]
            val col = i % art.cols
            val row = i / art.cols
            paint.color = colorFor(i)
            canvas.drawText(
                art.chars, i, 1,
                layout.offsetX + col * layout.cellW,
                layout.offsetY + row * layout.cellH + baselineShift,
                paint,
            )
        }
    }

    /** Renderiza o quadro com [revealed] caracteres digitados em [out]. */
    fun renderFrame(revealed: Int, cursorVisible: Boolean, out: Bitmap) {
        val target = revealed.coerceIn(0, order.size)
        if (target < drawn) {
            base.eraseColor(style.background)
            drawn = 0
        }
        drawRange(baseCanvas, drawn, target)
        drawn = target

        val c = Canvas(out)
        c.drawBitmap(base, 0f, 0f, null)
        if (cursorVisible) {
            val pos = if (target == 0) 0 else order[target - 1] + 1
            val col = pos % art.cols
            val row = (pos / art.cols).coerceAtMost(art.rows - 1)
            val x = layout.offsetX + col * layout.cellW
            val y = layout.offsetY + row * layout.cellH
            c.drawRect(x, y + layout.cellH * 0.1f, x + layout.cellW, y + layout.cellH * 0.9f, cursorPaint)
        }
    }

    /** Imagem final (tudo digitado), usada na pré-visualização. */
    fun renderFinal(): Bitmap {
        val out = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        renderFrame(order.size, false, out)
        return out
    }

    fun release() {
        base.recycle()
    }

    private companion object {
        fun mix(a: Int, b: Int, t: Float): Int {
            val r = ((a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * t).toInt()
            val g = ((a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * t).toInt()
            val bl = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t).toInt()
            return Color.rgb(r, g, bl)
        }

        /** Realça a saturação/brilho das cores originais para ficarem vivas no fundo preto. */
        fun boost(c: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)
            hsv[2] = (0.45f + hsv[2] * 0.75f).coerceAtMost(1f)
            return Color.HSVToColor(hsv)
        }
    }
}

/** Linha do tempo do vídeo: pausa inicial com cursor piscando, digitação e pausa final. */
class Timeline(
    private val totalChars: Int,
    typingSeconds: Float,
    val fps: Int = 30,
    leadSeconds: Float = 0.6f,
    holdSeconds: Float = 2.5f,
) {
    private val leadFrames = (leadSeconds * fps).roundToInt()
    private val typingFrames = max(1, (typingSeconds * fps).roundToInt())
    private val holdFrames = (holdSeconds * fps).roundToInt()
    val totalFrames = leadFrames + typingFrames + holdFrames

    fun revealedAt(frame: Int): Int {
        val f = frame - leadFrames
        if (f < 0) return 0
        if (f >= typingFrames) return totalChars
        return ((f + 1).toLong() * totalChars / typingFrames).toInt()
    }

    fun cursorAt(frame: Int): Boolean {
        val f = frame - leadFrames
        if (f in 0 until typingFrames) return true
        return (frame / (fps / 2)) % 2 == 0 // pisca a cada 0,5 s
    }

    fun ptsUs(frame: Int): Long = frame * 1_000_000L / fps
}
