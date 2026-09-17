/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

/**
 * [dstX]/[dstY]/[dstW]/[dstH] is the letterbox rect in VIEW px (top-pinned,
 * horizontally centered); used for input mapping. It is DERIVED from the
 * buffer-space rect below (not computed independently), so it agrees with
 * where the compositor actually draws the buffer within 1px on every edge.
 *
 * [bufW]/[bufH] is the SurfaceHolder buffer size ([android.view.SurfaceHolder.setFixedSize]),
 * and [bufDstX]/[bufDstY]/[bufDstW]/[bufDstH] is the same letterbox rect but in
 * BUFFER px: the buffer is sized so that rect is exactly the framebuffer's own
 * size whenever [RenderGeometry.compute]'s `maxBuffer` allows it, so the
 * renderer can `drawBitmap` at 1:1 (no CPU scaling) and let the compositor do
 * the final scale-up from buffer px to view px when presenting the surface.
 */
data class RenderGeom(
    val dstX: Int,
    val dstY: Int,
    val dstW: Int,
    val dstH: Int,
    val bufW: Int,
    val bufH: Int,
    val bufDstX: Int,
    val bufDstY: Int,
    val bufDstW: Int,
    val bufDstH: Int,
)

object RenderGeometry {
    /** Sane ceiling for a SurfaceHolder buffer / Bitmap dimension. */
    const val DEFAULT_MAX_BUFFER = 4096

    /**
     * Pure geometry function: no Android types, safe to unit test directly.
     * Zero/negative [viewW]/[viewH]/[fbW]/[fbH] are coerced to 1 first, so the
     * result is always a valid, non-degenerate (>= 1x1 everywhere) geometry.
     */
    fun compute(viewW: Int, viewH: Int, fbW: Int, fbH: Int, maxBuffer: Int = DEFAULT_MAX_BUFFER): RenderGeom {
        val vw = viewW.coerceAtLeast(1)
        val vh = viewH.coerceAtLeast(1)
        val fw = fbW.coerceAtLeast(1)
        val fh = fbH.coerceAtLeast(1)
        val vwD = vw.toDouble()
        val vhD = vh.toDouble()
        val fwD = fw.toDouble()
        val fhD = fh.toDouble()

        // Buffer space is computed first, in Double with rounding (not
        // truncation): on the fit axis the raw value is mathematically
        // exactly fbW or fbH, and Float truncation could shave it down to
        // fbW-1/fbH-1, clipping the last framebuffer row/column. Clamp
        // uniformly (preserving aspect) if that would exceed maxBuffer, then
        // coerce each buffer dimension to be at least its own letterbox
        // dimension so the letterbox rect always fits inside the buffer.
        val scaleD = minOf(vwD / fwD, vhD / fhD)
        val rawBufW = vwD / scaleD
        val rawBufH = vhD / scaleD
        val largest = maxOf(rawBufW, rawBufH)
        val clamp = if (largest > maxBuffer) maxBuffer / largest else 1.0

        val bufDstW = Math.round(fwD * clamp).toInt().coerceIn(1, maxBuffer)
        val bufDstH = Math.round(fhD * clamp).toInt().coerceIn(1, maxBuffer)
        val bufW = Math.round(rawBufW * clamp).toInt().coerceIn(bufDstW, maxBuffer)
        val bufH = Math.round(rawBufH * clamp).toInt().coerceIn(bufDstH, maxBuffer)
        val bufDstX = ((bufW - bufDstW) / 2).coerceAtLeast(0)
        val bufDstY = 0

        // View space is DERIVED from the buffer-space rect (single source of
        // truth) instead of being recomputed independently from the fit
        // scale: that used to leave the Float-truncated view rect and the
        // Double-rounded buffer rect disagreeing by a few px once the
        // compositor scaled the buffer up to the view, which threw off touch
        // mapping (view rect) versus the drawn edge (buffer rect). Mapping
        // buffer px back to view px via the buffer's own size keeps both
        // within 1px of each other on every edge. Top pinning (dY = 0) is
        // kept explicit; horizontal centering falls out of bufDstX already
        // being centered in the buffer.
        val dW = Math.round(bufDstW.toDouble() * vwD / bufW).toInt().coerceIn(1, vw)
        val dH = Math.round(bufDstH.toDouble() * vhD / bufH).toInt().coerceIn(1, vh)
        val dX = Math.round(bufDstX.toDouble() * vwD / bufW).toInt().coerceIn(0, vw - dW)
        val dY = 0

        return RenderGeom(dX, dY, dW, dH, bufW, bufH, bufDstX, bufDstY, bufDstW, bufDstH)
    }
}
