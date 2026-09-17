/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

/**
 * Accumulates RFB damage rects between renders so a renderer can copy only
 * what changed instead of the whole framebuffer. Not thread-safe by itself:
 * callers hold the framebuffer lock (X11ViewModel.fbLock) around every call.
 */
class DamageTracker {
    private val pending = ArrayList<VncRect>()

    /** Appends [rects]; once pending exceeds [CAP] entries, collapses them to one bounding box. */
    fun add(rects: List<VncRect>) {
        if (rects.isEmpty()) return
        pending.addAll(rects)
        if (pending.size > CAP) {
            val box = boundingBox(pending)
            pending.clear()
            pending.add(box)
        }
    }

    /** Marks the whole [w] x [h] frame dirty, replacing any finer-grained pending rects. */
    fun invalidateAll(w: Int, h: Int) {
        pending.clear()
        pending.add(VncRect(0, 0, w, h))
    }

    /** Returns pending damage and clears it. An empty result means nothing new to draw. */
    fun drain(): List<VncRect> {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList(pending)
        pending.clear()
        return out
    }

    private fun boundingBox(rects: List<VncRect>): VncRect {
        var minX = rects[0].x
        var minY = rects[0].y
        var maxX = rects[0].x + rects[0].w
        var maxY = rects[0].y + rects[0].h
        for (i in 1 until rects.size) {
            val r = rects[i]
            if (r.x < minX) minX = r.x
            if (r.y < minY) minY = r.y
            if (r.x + r.w > maxX) maxX = r.x + r.w
            if (r.y + r.h > maxY) maxY = r.y + r.h
        }
        return VncRect(minX, minY, maxX - minX, maxY - minY)
    }

    private companion object {
        const val CAP = 32
    }
}
