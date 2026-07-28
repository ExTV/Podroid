/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

data class ChartSeries(
    val values: List<Float>,
    val color: Color,
)

@Composable
fun LiveLineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    yMax: Float? = null,
    heightDp: Int = 120,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Horizontal grid (Azure-like)
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        val peak = series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
        val maxY = (yMax ?: (peak * 1.15f)).coerceAtLeast(0.01f)

        series.forEach { s ->
            if (s.values.size < 2) return@forEach
            val path = Path()
            val n = s.values.size
            s.values.forEachIndexed { i, v ->
                val x = if (n == 1) 0f else w * i / (n - 1)
                val y = h - (v.coerceIn(0f, maxY) / maxY) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = s.color,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
