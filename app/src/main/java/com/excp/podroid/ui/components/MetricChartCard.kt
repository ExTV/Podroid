/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Azure-style metric tile: title, live line chart, colored legend rows.
 */
package com.excp.podroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.excp.podroid.ui.theme.PodroidTokens

data class MetricLegend(
    val label: String,
    val value: String,
    val color: Color,
    val subtitle: String? = null,
)

@Composable
fun MetricChartCard(
    title: String,
    legends: List<MetricLegend>,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    yMax: Float? = null,
    unavailable: String? = null,
    timeHint: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(PodroidTokens.Spacing.MD),
            verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (unavailable != null) {
                Text(
                    text = unavailable,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(top = PodroidTokens.Spacing.LG),
                )
            } else {
                LiveLineChart(series = series, yMax = yMax)
                if (timeHint != null) {
                    Text(
                        text = timeHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            legends.forEach { legend ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(3.dp)
                            .height(28.dp)
                            .background(legend.color, RoundedCornerShape(1.dp)),
                    )
                    Spacer(modifier = Modifier.width(PodroidTokens.Spacing.SM))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = legend.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (legend.subtitle != null) {
                            Text(
                                text = legend.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = legend.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
