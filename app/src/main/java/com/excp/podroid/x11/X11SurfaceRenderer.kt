/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import com.excp.podroid.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only (BuildConfig.DEBUG) snapshot of RFB-side stats for the
 * once-per-second X11Stats log: bytes read off the socket, framebuffer
 * updates applied, damaged-row copy time, time from a FramebufferUpdate
 * message's first byte to its full decode (transfer + decode, excludes the
 * idle wait before the message), and the currently advertised encoding.
 */
data class RfbDebugSnapshot(
    val bytes: Long,
    val updates: Long,
    val copyNanos: Long,
    val rxNanos: Long,
    val encoding: String,
)

/**
 * Dedicated render thread for the X11 viewer's SurfaceView. Paces
 * presentation to vsync via [Choreographer] (at most one present per vsync),
 * keeps the SurfaceHolder's own buffer sized per [RenderGeometry] so the
 * compositor does the scale-up to the view's on-screen size instead of a
 * per-frame CPU-scaled `drawBitmap`, and blits only the RFB-damaged rows each
 * frame via [SurfaceHolder.lockCanvas] with a dirty rect.
 *
 * No Compose imports: X11Screen owns the SurfaceView, its lifecycle, and the
 * ViewModel, and feeds geometry / frame-ready notifications in through the
 * plain function references below.
 */
class X11SurfaceRenderer(
    private val holder: SurfaceHolder,
    // Non-draining peek at the current framebuffer array's length. Checked
    // BEFORE calling [withFrame] so a frame whose Bitmap hasn't been resized
    // to match a just-landed server resize yet can skip the copy WITHOUT
    // draining the pending damage; draining here would discard the very
    // invalidateAll() the eventually-resized Bitmap needs to fill itself in.
    private val frameBufferSize: () -> Int,
    // X11ViewModel.withFrame: atomically drains pending damage and hands back
    // the framebuffer under the ViewModel's fbLock.
    private val withFrame: ((fb: IntArray, fbW: Int, fbH: Int, damage: List<VncRect>) -> Unit) -> Unit,
    // X11ViewModel.debugSnapshotAndReset: only called when BuildConfig.DEBUG,
    // once per second, to assemble the combined X11Stats log line below.
    private val debugRfbStats: () -> RfbDebugSnapshot,
    // X11ViewModel.debugPresentHw: x11-debug.conf's present=hw|sw setting for
    // this session. Always false (sw) in release builds, which never read
    // that file. Consulted once per Surface, at its first present.
    private val presentHw: () -> Boolean,
) {
    private val thread = HandlerThread("X11Render").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var choreographer: Choreographer? = null
    private val framePending = AtomicBoolean(false)
    @Volatile private var released = false

    // Render-thread-confined state below; only ever touched on `handler`'s looper.
    private var bitmap: Bitmap? = null
    private var fbW = 0
    private var fbH = 0
    private var geom = RenderGeometry.compute(1, 1, 1, 1)
    private var lastBufW = -1
    private var lastBufH = -1
    private var forceFullRedraw = true

    // Debug-only (BuildConfig.DEBUG) counters for the once-per-second X11Stats
    // log; render-thread-confined like the other fields above, reset each time
    // the line is logged. pixMs = setPixels into the bitmap (doFrame); drawMs =
    // lock + draw + unlockAndPost (present).
    private var debugPresents = 0L
    private var debugPixNanos = 0L
    private var debugDrawNanos = 0L
    private var debugLastLogNanos = 0L

    // Render-thread-confined present-mode state. A Surface that has had
    // lockHardwareCanvas() called stays HWUI-backed, so a software lockCanvas
    // on it throws or returns null: the mode is therefore chosen once per
    // Surface (null = not chosen yet, reset by onSurfaceCreated) and never
    // switched from HW to SW on the same Surface. hwUnusable is sticky for the
    // session once hw drawing has thrown; that Surface then skips frames
    // (SKIP) and the next Surface picks SW.
    private var surfaceMode: PresentMode? = null
    private var hwUnusable = false

    private enum class PresentMode { HW, SW, SKIP }

    init {
        handler.post { choreographer = Choreographer.getInstance() }
    }

    /** Called from the ViewModel's onFrame hook after a framebuffer update lands.
     *  Coalesces bursts: a callback already pending absorbs this request. */
    fun requestFrame() {
        if (released || !framePending.compareAndSet(false, true)) return
        handler.post {
            val c = choreographer
            if (released) { framePending.set(false); return@post }
            if (c == null) {
                // First call can race Choreographer.getInstance() in init;
                // draw directly rather than dropping the frame.
                framePending.set(false)
                doFrame()
                return@post
            }
            c.postFrameCallback {
                framePending.set(false)
                if (!released) doFrame()
            }
        }
    }

    /** New view/framebuffer geometry: recreates the Bitmap when the framebuffer
     *  size changed and/or resizes the surface's own buffer when the target
     *  buffer size changed, then forces a full redraw either way. */
    fun updateGeometry(newFbW: Int, newFbH: Int, newGeom: RenderGeom) {
        if (released) return
        handler.post {
            if (newFbW != fbW || newFbH != fbH) {
                fbW = newFbW; fbH = newFbH
                bitmap = if (fbW > 0 && fbH > 0) Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888) else null
                forceFullRedraw = true
            }
            geom = newGeom
            if (geom.bufW != lastBufW || geom.bufH != lastBufH) {
                lastBufW = geom.bufW; lastBufH = geom.bufH
                val w = geom.bufW; val h = geom.bufH
                // setFixedSize interacts with the Surface's producer side; post
                // it to the main thread as SurfaceHolder expects.
                mainHandler.post { runCatching { holder.setFixedSize(w, h) } }
                forceFullRedraw = true
            }
        }
        requestFrame()
    }

    /** New Surface instance: re-pick the present mode at its first present
     *  and force a full redraw (buffer contents are undefined). */
    fun onSurfaceCreated() {
        if (released) return
        handler.post { surfaceMode = null; forceFullRedraw = true }
        requestFrame()
    }

    /** Surface changed: buffer contents are undefined, so force a full redraw. */
    fun onSurfaceChanged() {
        if (released) return
        handler.post { forceFullRedraw = true }
        requestFrame()
    }

    /** Stops the render thread. Does not recycle the Bitmap; a frame may still
     *  be mid-flight when this is called; let GC reclaim it once the thread
     *  (its only other reader) has actually quit. */
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun doFrame() {
        val bmp = bitmap ?: return
        val bw = bmp.width
        val bh = bmp.height
        var dirty: Rect? = null
        if (frameBufferSize() == bw * bh) {
            withFrame { fb, fw, _, damage ->
                if (fb.size != bw * bh) {
                    // A resize landed on the RFB thread since the check above;
                    // our Bitmap hasn't caught up yet (updateGeometry does
                    // that once Compose collects the new fbSize). Skip the
                    // copy and retry on the next frame.
                    forceFullRedraw = true
                    return@withFrame
                }
                val debugT0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                for (r in damage) {
                    val rx = r.x.coerceIn(0, bw)
                    val ry = r.y.coerceIn(0, bh)
                    val rw = (r.x + r.w).coerceAtMost(bw) - rx
                    val rh = (r.y + r.h).coerceAtMost(bh) - ry
                    if (rw <= 0 || rh <= 0) continue
                    bmp.setPixels(fb, ry * fw + rx, fw, rx, ry, rw, rh)
                    val rect = Rect(rx, ry, rx + rw, ry + rh)
                    dirty = dirty?.apply { union(rect) } ?: rect
                }
                if (BuildConfig.DEBUG) debugPixNanos += System.nanoTime() - debugT0
            }
        }
        val fullRedraw = forceFullRedraw
        if (dirty == null && !fullRedraw) return
        forceFullRedraw = false
        present(bmp, dirty, fullRedraw)
    }

    private fun present(bmp: Bitmap, bitmapDirty: Rect?, fullRedraw: Boolean) {
        val g = geom
        val letterbox = Rect(g.bufDstX, g.bufDstY, g.bufDstX + g.bufDstW, g.bufDstY + g.bufDstH)
        val mode = surfaceMode ?: (
            if (presentHw() && !hwUnusable && bmp.byteCount < HW_MAX_BITMAP_BYTES) PresentMode.HW else PresentMode.SW
        ).also { surfaceMode = it }
        when (mode) {
            PresentMode.HW -> presentHardware(bmp, letterbox)
            PresentMode.SW -> presentSoftware(bmp, letterbox, bitmapDirty, fullRedraw)
            // hw drawing threw on this Surface: nothing more can be posted to
            // it; keep the bitmap and redraw it all on the next Surface.
            PresentMode.SKIP -> forceFullRedraw = true
        }
    }

    /** Hardware-canvas present: always a full redraw (hardware canvases don't
     *  preserve prior content or honor a dirty rect), so damage/geometry
     *  semantics are unaffected: only the bitmap-to-screen blit changes.
     *  A null lock (surface not ready, drawing stopped, or a swallowed lock
     *  error) skips this frame only. A RuntimeException from drawing/posting
     *  (e.g. a bitmap too large for a hardware canvas) is caught here so it
     *  never escapes doFrame, and marks hw unusable for the session. */
    private fun presentHardware(bmp: Bitmap, letterbox: Rect) {
        val debugT0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val canvas = try {
            holder.lockHardwareCanvas()
        } catch (e: RuntimeException) {
            null
        }
        if (canvas == null) {
            forceFullRedraw = true
            return
        }
        try {
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bmp, null, letterbox, null)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        } catch (e: RuntimeException) {
            hwUnusable = true
            surfaceMode = PresentMode.SKIP
            forceFullRedraw = true
            Log.w("X11Stats", "hw present failed; skipping frames on this surface, sw from the next one", e)
            return
        }
        if (BuildConfig.DEBUG) {
            debugPresents++
            debugDrawNanos += System.nanoTime() - debugT0
            maybeLogDebugStats(usedHw = true)
        }
    }

    private fun presentSoftware(bmp: Bitmap, letterbox: Rect, bitmapDirty: Rect?, fullRedraw: Boolean) {
        val g = geom
        val unclamped = g.bufDstW == bmp.width && g.bufDstH == bmp.height
        val requestDirty = when {
            fullRedraw || !unclamped -> null
            bitmapDirty != null -> Rect(
                bitmapDirty.left + g.bufDstX, bitmapDirty.top + g.bufDstY,
                bitmapDirty.right + g.bufDstX, bitmapDirty.bottom + g.bufDstY,
            )
            else -> null
        }
        val debugT0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val canvas = try {
            holder.lockCanvas(requestDirty)
        } catch (e: Exception) {
            null
        }
        if (canvas == null) {
            // Damage was already drained and copied into the bitmap; redraw
            // all of it on the next successful present instead of losing it.
            forceFullRedraw = true
            return
        }
        try {
            // lockCanvas(dirty) clips the canvas to what it actually agreed to
            // redraw, which may be larger than requested (e.g. the first
            // frame after create/resize, or a null/full request): read that
            // back rather than trusting requestDirty, and redraw everything
            // the returned clip covers.
            val actual = Rect()
            if (!canvas.getClipBounds(actual)) actual.set(0, 0, g.bufW, g.bufH)
            canvas.drawColor(Color.BLACK)
            val dst = Rect(actual)
            if (dst.intersect(letterbox)) {
                if (unclamped) {
                    val src = Rect(dst.left - g.bufDstX, dst.top - g.bufDstY, dst.right - g.bufDstX, dst.bottom - g.bufDstY)
                    canvas.drawBitmap(bmp, src, dst, null)
                } else {
                    // Clamped case only: the letterbox rect in buffer px is
                    // smaller than the Bitmap, so this draw scales (rare:
                    // only when the framebuffer itself is huge).
                    canvas.drawBitmap(bmp, null, letterbox, null)
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        if (BuildConfig.DEBUG) {
            debugPresents++
            debugDrawNanos += System.nanoTime() - debugT0
            maybeLogDebugStats(usedHw = false)
        }
    }

    /** Debug-only: logs one X11Stats line at most once per second, combining
     *  this renderer's presents/pix-time/draw-time with the ViewModel's
     *  RFB-side bytes/updates/copy-time. Called only from [presentHardware] /
     *  [presentSoftware], themselves gated on BuildConfig.DEBUG, so this never
     *  runs in a release build. */
    private fun maybeLogDebugStats(usedHw: Boolean) {
        val now = System.nanoTime()
        if (now - debugLastLogNanos < 1_000_000_000L) return
        debugLastLogNanos = now
        val snap = debugRfbStats()
        val presents = debugPresents
        val pixNanos = debugPixNanos
        val drawNanos = debugDrawNanos
        debugPresents = 0L
        debugPixNanos = 0L
        debugDrawNanos = 0L
        Log.d(
            "X11Stats",
            "updates=%d bytesKB=%d copyMs=%.1f rxMs=%.1f presents=%d pixMs=%.1f drawMs=%.1f fb=%dx%d enc=%s present=%s".format(
                snap.updates, snap.bytes / 1024, snap.copyNanos / 1_000_000.0, snap.rxNanos / 1_000_000.0,
                presents, pixNanos / 1_000_000.0, drawNanos / 1_000_000.0, fbW, fbH, snap.encoding,
                if (usedHw) "hw" else "sw",
            ),
        )
    }

    private companion object {
        // Hardware canvases reject bitmaps above RecordingCanvas.MAX_BITMAP_SIZE
        // (about 100 MB floor); stay safely below it when picking HW.
        const val HW_MAX_BITMAP_BYTES = 90 * 1024 * 1024
    }
}
