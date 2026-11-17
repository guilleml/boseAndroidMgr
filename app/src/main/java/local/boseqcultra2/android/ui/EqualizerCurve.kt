// Curva del ecualizador de 3 bandas que se arrastra con el dedo.
//
// - La curva dibuja SIEMPRE los valores confirmados por los auriculares.
// - Mientras se arrastra, o mientras un valor enviado espera confirmación, se dibuja un anillo
//   en el valor pedido. El valor se envía al soltar, no en cada movimiento.
//
// En Compose no se hereda de View ni se sobrescribe onDraw: `Canvas { … }` recibe un DrawScope
// (parecido al Graphics2D de Swing) y `pointerInput` instala el manejo de gestos.
package local.boseqcultra2.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.boseqcultra2.android.protocol.EqBand
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun EqualizerCurve(
    bands: List<EqBand>,
    pending: Map<Int, Int>,
    enabled: Boolean,
    onCommit: (band: Int, value: Int) -> Unit,
    bandName: (EqBand) -> String,
    modifier: Modifier = Modifier,
) {
    if (bands.isEmpty()) return
    val sorted = bands.sortedBy { it.index }
    // Rango tomado de la respuesta de los auriculares (no fijado).
    val min = sorted.minOf { it.min }
    val max = sorted.maxOf { it.max }

    /** Banda y valor que se están arrastrando; null si no hay arrastre. */
    var draft by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // rememberUpdatedState: el gesto (que vive más que una recomposición) ve siempre los valores actuales.
    val currentBands by rememberUpdatedState(sorted)
    val commit by rememberUpdatedState(onCommit)

    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(enabled, min, max) {
                if (!enabled) return@pointerInput
                val geo = { Geometry(size.width.toFloat(), size.height.toFloat(), currentBands.size, min, max, 40.dp.toPx(), 16.dp.toPx(), 36.dp.toPx()) }
                fun nearestBand(x: Float): Int? {
                    val g = geo()
                    val i = (0 until currentBands.size).minBy { abs(g.x(it) - x) }
                    return if (abs(g.x(i) - x) <= 56.dp.toPx()) i else null
                }
                detectDragGestures(
                    onDragStart = { start ->
                        nearestBand(start.x)?.let { i -> draft = currentBands[i].index to geo().valueAt(start.y) }
                    },
                    onDrag = { change, _ ->
                        draft?.let { (band, _) -> draft = band to geo().valueAt(change.position.y) }
                    },
                    onDragEnd = {
                        draft?.let { (band, value) -> commit(band, value) }
                        draft = null
                    },
                    onDragCancel = { draft = null },
                )
            }
            .pointerInput(enabled, min, max) {
                if (!enabled) return@pointerInput
                // Un toque también fija el valor de la banda más cercana.
                detectTapGestures { p ->
                    val g = Geometry(size.width.toFloat(), size.height.toFloat(), currentBands.size, min, max, 40.dp.toPx(), 16.dp.toPx(), 36.dp.toPx())
                    val i = (0 until currentBands.size).minBy { abs(g.x(it) - p.x) }
                    if (abs(g.x(i) - p.x) <= 56.dp.toPx()) commit(currentBands[i].index, g.valueAt(p.y))
                }
            },
    ) {
        val g = Geometry(size.width, size.height, sorted.size, min, max, 40.dp.toPx(), 16.dp.toPx(), 36.dp.toPx())

        // Rejilla: máximo, 0 y mínimo, con etiquetas.
        for (v in listOf(max, 0, min)) {
            val y = g.y(v)
            drawLine(
                if (v == 0) colors.outline else colors.outlineVariant,
                Offset(g.left, y), Offset(g.right, y), strokeWidth = 1.dp.toPx(),
                pathEffect = if (v == 0) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            val label = textMeasurer.measure(if (v > 0) "+$v" else "$v", labelStyle)
            drawText(label, topLeft = Offset(g.left - label.size.width - 8.dp.toPx(), y - label.size.height / 2))
        }
        // Guías verticales y nombre de cada banda.
        sorted.forEachIndexed { i, b ->
            drawLine(colors.outlineVariant, Offset(g.x(i), g.top), Offset(g.x(i), g.bottom), strokeWidth = 1.dp.toPx())
            val name = textMeasurer.measure(bandName(b), labelStyle.copy(fontSize = 12.sp, color = colors.onSurface))
            drawText(name, topLeft = Offset(g.x(i) - name.size.width / 2, g.bottom + 12.dp.toPx()))
        }

        // Curva suave con los valores CONFIRMADOS (cúbicas con puntos de control a media distancia).
        val pts = sorted.mapIndexed { i, b -> Offset(g.x(i), g.y(b.value)) }
        val curve = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val midX = (pts[i - 1].x + pts[i].x) / 2
                cubicTo(midX, pts[i - 1].y, midX, pts[i].y, pts[i].x, pts[i].y)
            }
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(pts.last().x, g.y(0)); lineTo(pts.first().x, g.y(0)); close()
        }
        val lineColor = if (enabled) colors.primary else colors.outline
        drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0.03f))))
        drawPath(curve, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        pts.forEach { drawCircle(lineColor, radius = 7.dp.toPx(), center = it) }

        // Anillos: valor que se arrastra o que espera confirmación.
        sorted.forEachIndexed { i, b ->
            val target = draft?.takeIf { it.first == b.index }?.second ?: pending[b.index]
            if (target != null && target != b.value) {
                drawCircle(colors.tertiary, radius = 11.dp.toPx(), center = Offset(g.x(i), g.y(target)), style = Stroke(width = 3.dp.toPx()))
                val label = textMeasurer.measure(if (target > 0) "+$target" else "$target", labelStyle.copy(color = colors.tertiary))
                drawText(label, topLeft = Offset(g.x(i) + 14.dp.toPx(), g.y(target) - label.size.height / 2))
            }
        }
    }
}

/** Conversión entre valores del EQ y coordenadas del lienzo. */
private class Geometry(
    width: Float, height: Float, private val count: Int,
    private val min: Int, private val max: Int, padLeft: Float, padTop: Float, padBottom: Float,
) {
    val left = padLeft
    val right = width - padLeft / 2
    val top = padTop
    val bottom = height - padBottom

    fun x(i: Int) = if (count <= 1) (left + right) / 2 else left + i * (right - left) / (count - 1)
    fun y(v: Int) = bottom - (v - min).toFloat() / (max - min) * (bottom - top)
    fun valueAt(y: Float): Int =
        (min + (bottom - y) / (bottom - top) * (max - min)).roundToInt().coerceIn(min, max)
}
