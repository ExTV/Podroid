/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.x11

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.x11.RenderGeom
import com.excp.podroid.x11.VncSize

@Composable
internal fun X11CursorOverlay(
    viewModel: X11ViewModel,
    geometry: RenderGeom,
    framebuffer: VncSize,
    modifier: Modifier,
) {
    val shape by viewModel.cursorShape.collectAsStateWithLifecycle()
    val cursor = shape ?: return
    if (cursor.pixels.isEmpty()) return
    val image = remember(cursor) {
        Bitmap.createBitmap(cursor.pixels, cursor.width, cursor.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val position = viewModel.cursor.collectAsStateWithLifecycle()
    Canvas(modifier) {
        // Read position in the draw phase: motion redraws only this overlay,
        // without recomposing the viewer or waiting for another VNC frame.
        val p = position.value
        clipRect(
            geometry.dstX.toFloat(), geometry.dstY.toFloat(),
            (geometry.dstX + geometry.dstW).toFloat(), (geometry.dstY + geometry.dstH).toFloat(),
        ) {
            withTransform({
                translate(geometry.dstX.toFloat(), geometry.dstY.toFloat())
                scale(geometry.dstW.toFloat() / framebuffer.w, geometry.dstH.toFloat() / framebuffer.h, Offset.Zero)
            }) {
                drawImage(image, Offset((p.x - cursor.hotspotX).toFloat(), (p.y - cursor.hotspotY).toFloat()))
            }
        }
    }
}
