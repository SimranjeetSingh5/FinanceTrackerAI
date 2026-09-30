package com.financetracker.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.ai.market.PriceSeries
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A one-year price chart with a scrub-to-inspect gesture.
 *
 * Drawn with Compose primitives on a [Canvas] — the same approach as the Analytics screen, and
 * for the same reason: a charting library would be a large dependency to carry for one line.
 *
 * The [PriceSeries] is already cleaned by the data layer (nulls for shut-market days dropped),
 * so this only has to map values to pixels.
 */
@Composable
fun PriceLineChart(
    series: PriceSeries,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 180.dp
) {
    val values = remember(series) { series.points.map { it.close } }
    if (values.size < 2) return

    val minValue = values.min()
    val maxValue = values.max()
    val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val firstDate = series.points.first().epochSeconds
    val lastDate = series.points.last().epochSeconds
    val totalMillis = ((lastDate - firstDate) * 1000).coerceAtLeast(1L)

    val lineColor = if (values.last() >= values.first()) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    // Index of the point under the finger, or null when not scrubbing.
    var scrubIndex by remember(series) { mutableStateOf<Int?>(null) }
    val currencyFormatter = rememberCurrencyFormatter()

    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val headerDate = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val index = scrubIndex
            val shownValue = index?.let { values[it] } ?: values.last()
            val shownDate = index?.let { series.points[it].epochSeconds } ?: lastDate

            Text(
                currencyFormatter.format(shownValue),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = lineColor
            )
            Text(
                headerDate.format(Date(shownDate * 1000)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                // Scrubbing: report the nearest point so the header can show its value.
                .pointerInput(series) {
                    detectHorizontalDragGestures(
                        onDragEnd = { scrubIndex = null },
                        onDragCancel = { scrubIndex = null }
                    ) { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrubIndex = (fraction * (values.size - 1)).roundToInt()
                    }
                }
                .pointerInput(series) {
                    detectTapGestures(
                        onPress = { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            scrubIndex = (fraction * (values.size - 1)).roundToInt()
                            // Hold the value briefly on tap, then release.
                            kotlinx.coroutines.delay(1_500)
                            scrubIndex = null
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val padding = 8f
            val drawableH = h - padding * 2

            fun xAt(index: Int): Float {
                val t = (series.points[index].epochSeconds - firstDate).toFloat() / totalMillis
                return t * w
            }

            fun yAt(value: Double): Float {
                val t = ((value - minValue) / span).toFloat()
                // Inverted: the top of the canvas is the highest price.
                return padding + (1f - t) * drawableH
            }

            // Horizontal grid lines with the value labelled at the right.
            val gridLines = 3
            for (i in 0..gridLines) {
                val y = padding + (i.toFloat() / gridLines) * drawableH
                drawLine(
                    color = gridColor.copy(alpha = 0.35f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }

            val linePath = Path()
            val fillPath = Path()
            values.forEachIndexed { index, value ->
                val x = xAt(index)
                val y = yAt(value)
                if (index == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(xAt(values.size - 1), h)
            fillPath.close()

            // Gradient under the line, fading to nothing at the baseline.
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(lineColor.copy(alpha = 0.28f), Color.Transparent)
                )
            )
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 3f)
            )

            // Baseline.
            drawLine(
                color = gridColor.copy(alpha = 0.6f),
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1.5f
            )

            // Scrub marker: dashed vertical line plus a dot on the series.
            scrubIndex?.let { index ->
                val x = xAt(index)
                val y = yAt(values[index])
                drawLine(
                    color = lineColor.copy(alpha = 0.6f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
                drawCircle(color = lineColor, radius = 6f, center = Offset(x, y))
                drawCircle(color = Color.White, radius = 2.5f, center = Offset(x, y))
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text(
                dateFormat.format(Date(firstDate * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Drag to inspect",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                dateFormat.format(Date(lastDate * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
