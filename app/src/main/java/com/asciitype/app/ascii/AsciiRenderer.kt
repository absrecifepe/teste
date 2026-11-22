package com.asciitype.app.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.StringRes
import com.asciitype.app.R
import kotlin.math.ceil
import kotlin.math.floor
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
    /** Tom mínimo das letras (0 = cor do fundo, 1 = cor da tinta). Mais alto = mais contraste. */
    val minTone: Float = 0.35f,
) {
    // Fundo branco, letras em tons de cinza (mais escuras onde a foto é mais escura).
    PAPER(R.string.style_paper, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), denseIsDark = true, minTone = 0.55f),
    MATRIX(R.string.style_matrix, 0xFF050805.toInt(), 0xFF39FF6A.toInt(), 0xFF39FF6A.toInt()),
    TERMINAL(R.string.style_terminal, 0xFF0A0A0A.toInt(), 0xFFF2F2F2.toInt(), 0xFFF2F2F2.toInt()),
    COLOR(R.string.style_color, 0xFF000000.toInt(), Color.WHITE, Color.WHITE, useOriginalColors = true),
    AMBER(R.string.style_amber, 0xFF0D0700.toInt(), 0xFFFFB000.toInt(), 0xFFFFB000.toInt()),
}

/** Geometria do quadro: grade de caracteres + tamanho do vídeo em pixels. */
data class FrameLayout(
    val cols: Int,
    val rows: Int,
    /** Linhas ocupadas pela foto (o restante, no topo, é o texto). */
    val imageRows: Int,
    val cellW: Float,
    val cellH: Float,
    val textSize: Float,
    val width: Int,
    val height: Int,
    val offsetX: Float,
    val offsetY: Float,
) {
    companion object {
        fun monoPaint() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        /**
         * Calcula a grade para uma imagem [imgW]x[imgH] com [cols] colunas (+ [extraRows] linhas de
         * texto no topo), cabendo em [maxWidth]x[maxHeight]. Largura/altura finais são múltiplos de 16.
         */
        fun compute(imgW: Int, imgH: Int, cols: Int, maxWidth: Int, maxHeight: Int, extraRows: Int = 0): FrameLayout {
            val p = monoPaint().apply { textSize = 100f }
            val charW = p.measureText("M")
            val fm = p.fontMetrics
            val lineH = fm.descent - fm.ascent
            val ratio = lineH / charW // altura/largura de uma célula

            val imageRows = max(1, (cols * imgH.toFloat() / imgW / ratio).roundToInt())
            val rows = imageRows + extraRows
            val marginCells = 2f
            var cellW = maxWidth / (cols + marginCells * 2)
            var cellH = cellW * ratio
            val needH = (rows + marginCells * 2 / ratio) * cellH
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
                imageRows = imageRows,
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
 * digitados são acumulados, então cada quadro sem zoom só desenha os caracteres novos.
 * Com zoom, desenha como texto vetorial (nítido) apenas as células visíveis, com uma
 * "câmera" que segue o cursor suavemente.
 */
class AsciiRenderer(
    private val art: AsciiArt,
    private val layout: FrameLayout,
    private val style: ColorStyle,
) {
    /** Ordem de digitação: primeiro o texto do usuário, depois as demais células em ordem de leitura. */
    private val order: IntArray = run {
        val first = art.firstCells.filter { art.chars[it] != ' ' }
        val firstSet = first.toHashSet()
        (first + art.chars.indices.filter { art.chars[it] != ' ' && it !in firstSet }).toIntArray()
    }
    val typedCount: Int get() = order.size

    /** Posição de cada célula na ordem de digitação (-1 = espaço). */
    private val rank = IntArray(art.chars.size) { -1 }.also { r -> order.forEachIndexed { k, i -> r[i] = k } }

    private val paint = FrameLayout.monoPaint().apply { textSize = layout.textSize }
    private val cursorPaint = Paint().apply { color = this@AsciiRenderer.style.cursor }
    private val baselineShift = -paint.fontMetrics.ascent

    private val base: Bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
    private val baseCanvas = Canvas(base)
    private var drawn = 0

    // Câmera (centro do enquadramento, em pixels do quadro).
    private var camInit = false
    private var camX = 0f
    private var camY = 0f

    init {
        base.eraseColor(style.background)
    }

    private fun colorFor(i: Int): Int {
        val k = style.minTone + (1f - style.minTone) * art.ink[i]
        val c = if (style.useOriginalColors) boost(art.rgb[i]) else style.ink
        // Mais "tinta" = mais próximo da cor da tinta; menos = mais próximo do fundo.
        return mix(style.background, c, k)
    }

    private fun drawCell(canvas: Canvas, i: Int) {
        paint.color = colorFor(i)
        canvas.drawText(
            art.chars, i, 1,
            layout.offsetX + (i % art.cols) * layout.cellW,
            layout.offsetY + (i / art.cols) * layout.cellH + baselineShift,
            paint,
        )
    }

    /** Célula onde o cursor está, dado o número de caracteres digitados. */
    private fun cursorCell(revealed: Int): Int {
        if (revealed == 0) return order.firstOrNull() ?: 0
        return (order[revealed - 1] + 1).coerceAtMost(art.cols * art.rows - 1)
    }

    private fun drawCursor(canvas: Canvas, cell: Int) {
        val x = layout.offsetX + (cell % art.cols) * layout.cellW
        val y = layout.offsetY + (cell / art.cols) * layout.cellH
        canvas.drawRect(x, y + layout.cellH * 0.1f, x + layout.cellW, y + layout.cellH * 0.9f, cursorPaint)
    }

    /**
     * Renderiza em [out] o quadro com [revealed] caracteres digitados, aproximado [zoom] vezes
     * (1 = imagem inteira). Deve ser chamado quadro a quadro, em ordem.
     */
    fun renderFrame(revealed: Int, cursorVisible: Boolean, zoom: Float, out: Bitmap) {
        val target = revealed.coerceIn(0, order.size)
        if (target < drawn) {
            base.eraseColor(style.background)
            drawn = 0
            camInit = false
        }
        for (k in drawn until target) drawCell(baseCanvas, order[k])
        drawn = target

        val cell = cursorCell(target)
        val cx = layout.offsetX + (cell % art.cols + 0.5f) * layout.cellW
        val cy = layout.offsetY + (cell / art.cols + 0.5f) * layout.cellH
        if (!camInit) {
            camX = cx; camY = cy; camInit = true
        } else {
            camX += (cx - camX) * 0.12f
            camY += (cy - camY) * 0.12f
        }

        val c = Canvas(out)
        if (zoom <= 1.001f) {
            c.drawBitmap(base, 0f, 0f, null)
            if (cursorVisible) drawCursor(c, cell)
            return
        }

        // Janela visível; ao reduzir o zoom, o limite empurra a câmera naturalmente para o centro.
        val w = layout.width.toFloat()
        val h = layout.height.toFloat()
        val hw = w / (2 * zoom)
        val hh = h / (2 * zoom)
        val fx = camX.coerceIn(hw, w - hw)
        val fy = camY.coerceIn(hh, h - hh)
        val left = fx - hw
        val top = fy - hh

        c.drawColor(style.background)
        c.save()
        c.scale(zoom, zoom)
        c.translate(-left, -top)
        val col0 = floor((left - layout.offsetX) / layout.cellW).toInt().coerceAtLeast(0)
        val col1 = ceil((left + 2 * hw - layout.offsetX) / layout.cellW).toInt().coerceAtMost(art.cols - 1)
        val row0 = floor((top - layout.offsetY) / layout.cellH).toInt().coerceAtLeast(0)
        val row1 = ceil((top + 2 * hh - layout.offsetY) / layout.cellH).toInt().coerceAtMost(art.rows - 1)
        for (row in row0..row1) {
            for (col in col0..col1) {
                val i = row * art.cols + col
                val r = rank[i]
                if (r in 0 until target) drawCell(c, i)
            }
        }
        if (cursorVisible) drawCursor(c, cell)
        c.restore()
    }

    /** Imagem final (tudo digitado), usada na pré-visualização. */
    fun renderFinal(): Bitmap {
        val out = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        renderFrame(order.size, false, 1f, out)
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

/**
 * Linha do tempo do vídeo:
 * 1. pausa inicial com cursor piscando;
 * 2. introdução: os primeiros [introChars] caracteres (o texto e algumas linhas da foto)
 *    digitados devagar, em [introRate] caracteres/s, com a câmera aproximada em [maxZoom];
 * 3. digitação principal por [typingSeconds], acelerando, enquanto o zoom se afasta;
 * 4. pausa final com a imagem completa.
 */
class Timeline(
    private val totalChars: Int,
    introChars: Int,
    typingSeconds: Float,
    private val maxZoom: Float = 1f,
    val fps: Int = 30,
    leadSeconds: Float = 0.6f,
    holdSeconds: Float = 2.5f,
    private val introRate: Float = 28f,
) {
    private val introChars = introChars.coerceIn(0, totalChars)
    private val leadFrames = (leadSeconds * fps).roundToInt()
    private val introFrames = if (this.introChars > 0) ceil(this.introChars / introRate * fps).toInt() else 0
    private val mainFrames = max(1, (typingSeconds * fps).roundToInt())
    private val holdFrames = (holdSeconds * fps).roundToInt()
    val totalFrames = leadFrames + introFrames + mainFrames + holdFrames

    /** Peso da parte linear da digitação principal, para começar na velocidade da introdução. */
    private val linearWeight: Float = run {
        val rest = totalChars - this.introChars
        if (this.introChars == 0 || rest <= 0) 1f
        else (introRate * typingSeconds / rest).coerceIn(0f, 1f)
    }

    fun revealedAt(frame: Int): Int {
        val g = frame - leadFrames
        if (g < 0) return 0
        if (g < introFrames) return ((g + 1).toLong() * introChars / introFrames).toInt()
        val h = g - introFrames
        if (h >= mainFrames) return totalChars
        val u = (h + 1f) / mainFrames
        val eased = linearWeight * u + (1f - linearWeight) * u * u
        return introChars + ((totalChars - introChars) * eased).toInt()
    }

    fun zoomAt(frame: Int): Float {
        if (maxZoom <= 1f) return 1f
        val g = frame - leadFrames
        if (g < introFrames) return maxZoom
        val p = (g - introFrames) / (mainFrames * 0.4f)
        if (p >= 1f) return 1f
        val s = p * p * (3 - 2 * p) // smoothstep
        return maxZoom + (1f - maxZoom) * s
    }

    fun cursorAt(frame: Int): Boolean {
        val g = frame - leadFrames
        if (g in 0 until introFrames + mainFrames) return true
        return (frame / (fps / 2)) % 2 == 0 // pisca a cada 0,5 s
    }

    fun ptsUs(frame: Int): Long = frame * 1_000_000L / fps
}
