/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.x11

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.x11.AudioStreamer
import com.excp.podroid.x11.DamageTracker
import com.excp.podroid.x11.EncodingPolicy
import com.excp.podroid.x11.ResolutionMode
import com.excp.podroid.x11.ResolutionPolicy
import com.excp.podroid.x11.ResolutionPreset
import com.excp.podroid.x11.RfbDebugSnapshot
import com.excp.podroid.x11.RotationLock
import com.excp.podroid.x11.TouchMode
import com.excp.podroid.x11.VncClient
import com.excp.podroid.x11.VncRect
import com.excp.podroid.x11.VncSize
import com.excp.podroid.x11.X11Constants
import com.excp.podroid.x11.X11Settings
import com.excp.podroid.x11.ZrleDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

sealed interface X11ConnectionState {
    object Disconnected : X11ConnectionState
    object Connecting : X11ConnectionState
    object Connected : X11ConnectionState
    data class Failed(val message: String) : X11ConnectionState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class X11ViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _connection = MutableStateFlow<X11ConnectionState>(X11ConnectionState.Disconnected)
    val connection: StateFlow<X11ConnectionState> = _connection.asStateFlow()

    val x11Settings = settings.x11Settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), X11Settings())

    private val _fbSize = MutableStateFlow(VncSize(X11Constants.FB_WIDTH, X11Constants.FB_HEIGHT))
    val fbSize: StateFlow<VncSize> = _fbSize.asStateFlow()

    val cursor = MutableStateFlow(android.graphics.Point(X11Constants.FB_WIDTH / 2, X11Constants.FB_HEIGHT / 2))

    @Volatile private var fbW = X11Constants.FB_WIDTH
    @Volatile private var fbH = X11Constants.FB_HEIGHT
    @Volatile var framebuffer: IntArray = IntArray(fbW * fbH); private set
    // Dedicated lock object so synchronized() is never on the reassigned framebuffer field.
    private val fbLock = Any()
    @Volatile private var scratch: IntArray = IntArray(fbW * fbH)
    private val zrle = ZrleDecoder()
    @Volatile private var screenId = 0
    @Volatile private var desiredW = 0; @Volatile private var desiredH = 0
    // Guarded by fbLock, like framebuffer itself.
    private val damageTracker = DamageTracker()

    // Renderer hook: invoked outside fbLock after each recorded update so the
    // renderer can schedule a redraw without the read loop depending on a
    // StateFlow (which conflates and can silently drop damage between ticks).
    @Volatile var onFrame: (() -> Unit)? = null

    /** Atomically hands the current framebuffer, its dimensions, and the drained
     *  pending damage to [block] under fbLock. */
    fun <T> withFrame(block: (fb: IntArray, fbW: Int, fbH: Int, damage: List<VncRect>) -> T): T =
        synchronized(fbLock) { block(framebuffer, fbW, fbH, damageTracker.drain()) }

    // Debug-only (BuildConfig.DEBUG) stats for the once-per-second X11Stats log
    // assembled in X11SurfaceRenderer. Written from the single-threaded read loop
    // in connect(), read and reset from the render thread via debugSnapshotAndReset().
    private val debugStats = X11DebugStats()
    // Effective present mode for the current session: whether the renderer
    // should try a hardware canvas, read from x11-debug.conf's present= key
    // (debug builds only; release always stays sw).
    @Volatile var debugPresentHw: Boolean = false; private set

    /** Debug-only: atomically snapshots and resets (bytesRead, updates, copyNanos, rxNanos). */
    fun debugSnapshotAndReset(): RfbDebugSnapshot = debugStats.snapshotAndReset()

    private val audio = AudioStreamer()
    private var sessionJob: Job? = null
    @Volatile private var rfbOut: OutputStream? = null
    @Volatile private var rfbSocket: Socket? = null
    // Set once a ZRLE session fails with a protocol/decode error; every later
    // session in this ViewModel's lifetime advertises Raw only.
    @Volatile private var zrleDisabled = false

    // All post-handshake RFB output (pointer, key, SetDesktopSize, and the
    // recurring FramebufferUpdateRequest from the read loop) flows through this
    // single-parallelism dispatcher. limitedParallelism(1) runs each launched
    // body one-at-a-time in dispatch (submission) order; because each body is a
    // non-suspending blocking write+flush, every RFB message is written
    // atomically and messages keep submission order (key-down before key-up,
    // press before release). Without this, writes on the multi-thread IO pool
    // and the read coroutine interleaved at byte granularity, desyncing the
    // VNC server. (The initial handshake/negotiate/first-update writes run
    // directly before Connected, where no concurrent write is possible yet.)
    private val rfbDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Submit a blocking RFB write onto the serialized writer (never blocks the caller). */
    private fun submitRfb(block: (OutputStream) -> Unit) {
        val out = rfbOut ?: return
        viewModelScope.launch(rfbDispatcher) { runCatching { block(out) } }
    }

    fun connect() {
        if (sessionJob?.isActive == true) return
        _connection.value = X11ConnectionState.Connecting
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            // Runs at most twice: a ZRLE session that fails with a protocol error
            // is torn down as usual and immediately retried once with Raw on a new
            // socket, without reporting Failed.
            var retryWithRaw: Boolean
            do {
                retryWithRaw = false
                var sessionUsedZrle = false
                val sock = Socket()
                try {
                    rfbSocket = sock
                    sock.connect(InetSocketAddress("127.0.0.1", X11Constants.VNC_PORT), 2000)
                    val rawInp = sock.getInputStream()
                    // Debug-only (BuildConfig.DEBUG): counts bytes read off the RFB
                    // socket for the X11Stats log; never wrapped in a release build.
                    val inp: InputStream = if (BuildConfig.DEBUG) {
                        CountingInputStream(rawInp) { n -> debugStats.addBytes(n) }
                    } else rawInp
                    val out = sock.getOutputStream()
                    rfbOut = out
                    // Each RFB session is a fresh zlib stream; reset the ZRLE inflater
                    // before the read loop so a reconnect doesn't feed a finished/leftover
                    // inflater (which yields corrupt output or DataFormatException).
                    zrle.reset()

                    // Debug-only (BuildConfig.DEBUG): x11-debug.conf lets a developer
                    // request ZRLE (and the present mode) without a rebuild. Release
                    // builds never read this file and never want ZRLE, so they always
                    // advertise VncClient.DEFAULT_ENCODINGS.
                    val wantZrle = if (BuildConfig.DEBUG) {
                        val cfg = X11DebugConfig.read(context)
                        debugPresentHw = cfg.presentHw
                        Log.d("X11Stats", "debug-config encoding=${cfg.encodingName} present=${if (cfg.presentHw) "hw" else "sw"}")
                        cfg.wantZrle
                    } else {
                        false
                    }
                    val encodings = EncodingPolicy.encodingsFor(wantZrle, zrleDisabled)
                    val usesZrle = encodings === VncClient.ZRLE_ENCODINGS
                    if (BuildConfig.DEBUG) debugStats.encodingName = if (usesZrle) "zrle" else "raw"

                    VncClient.handshake(inp, out)
                    VncClient.negotiatePixelFormat(out, encodings)
                    sessionUsedZrle = usesZrle
                    if (desiredW > 0) VncClient.requestDesktopSize(out, screenId, desiredW, desiredH)
                    VncClient.requestFramebufferUpdate(out, w = fbW, h = fbH, incremental = false)
                    _connection.value = X11ConnectionState.Connected
                    audio.start(viewModelScope)
                    var rxT0 = 0L
                    while (isActive) {
                        val upd = VncClient.readFramebufferUpdate(
                            inp, scratch, fbW, zrle,
                            onMessageStart = if (BuildConfig.DEBUG) { { rxT0 = System.nanoTime() } } else null,
                        )
                        if (BuildConfig.DEBUG) {
                            val rxElapsed = System.nanoTime() - rxT0
                            debugStats.recordRx(rxElapsed)
                        }
                        val ns = upd.newSize
                        if (ns != null && (ns.w != fbW || ns.h != fbH)) {
                            val nw = ns.w; val nh = ns.h
                            val fresh = IntArray(nw * nh)
                            // Swap the framebuffer, width, and height, and mark the whole new
                            // frame dirty in the same critical section, so a recomposition
                            // between resize and the next full frame can't read a mismatched
                            // fbW/fbH against the old array, or blit stale damage rects (or a
                            // stale bounding box) against the new size.
                            synchronized(fbLock) { framebuffer = fresh; fbW = nw; fbH = nh; damageTracker.invalidateAll(nw, nh) }
                            scratch = IntArray(nw * nh)
                            _fbSize.value = ns
                            cursor.value = android.graphics.Point(fbW / 2, fbH / 2)
                            // Route through the serialized writer so this full-update
                            // request can't byte-interleave with a concurrent input
                            // write. Capture the just-resized dimensions explicitly.
                            val rw = fbW; val rh = fbH
                            submitRfb { VncClient.requestFramebufferUpdate(it, w = rw, h = rh, incremental = false) }
                            // Skip the rest of this iteration: the old code used
                            // return@let here, which only exited the let lambda and
                            // then fell through to overwrite lastDamage with rects
                            // measured against the OLD geometry (the exact race the
                            // synchronized block above prevents) and fire a spurious
                            // incremental request.
                            continue
                        }
                        val debugT0 = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                        synchronized(fbLock) {
                            // scratch stays the authoritative full image (CopyRect reads
                            // from it), so only the damaged row-ranges need copying into
                            // the framebuffer the renderer reads.
                            for (r in upd.damage) {
                                for (row in 0 until r.h) {
                                    val base = (r.y + row) * fbW + r.x
                                    System.arraycopy(scratch, base, framebuffer, base, r.w)
                                }
                            }
                            damageTracker.add(upd.damage)
                        }
                        if (BuildConfig.DEBUG) {
                            val elapsed = System.nanoTime() - debugT0
                            debugStats.recordUpdate(elapsed)
                        }
                        onFrame?.invoke()
                        // Same serialized path as input writes: queued FIFO behind any
                        // in-flight pointer/key message rather than colliding with it on
                        // the socket. Cadence is unchanged (one request per frame); the
                        // next read() blocks until this flushes and the server responds.
                        submitRfb { VncClient.requestFramebufferUpdate(it, w = fbW, h = fbH, incremental = true) }
                    }
                } catch (e: Exception) {
                    // A user-initiated disconnect() cancels this job and closes the
                    // socket, which surfaces here as a SocketException; that is not a
                    // failure, so only report Failed when the job is still active (a
                    // genuine read/connect error). Cancellation flips isActive false
                    // before the close lands, so the finally falls through to
                    // Disconnected instead.
                    if (isActive && EncodingPolicy.shouldFallBack(e, sessionUsedZrle)) {
                        zrleDisabled = true
                        retryWithRaw = true
                        Log.w("X11ViewModel", "ZRLE session failed with a protocol error, reconnecting with Raw: ${e.message}")
                    } else if (isActive) {
                        _connection.value = X11ConnectionState.Failed(e.message ?: "unknown")
                    }
                } finally {
                    rfbOut = null
                    rfbSocket = null
                    // Close the socket on the serialized writer, queued AFTER any pending
                    // RFB writes (e.g. the button-up from disconnect()), so a teardown
                    // can't tear the socket down before a final message has flushed.
                    viewModelScope.launch(rfbDispatcher) { runCatching { sock.close() } }
                    audio.stop()
                    if (retryWithRaw) {
                        _connection.value = X11ConnectionState.Connecting
                    } else if (_connection.value !is X11ConnectionState.Failed) {
                        _connection.value = X11ConnectionState.Disconnected
                    }
                }
            } while (retryWithRaw && isActive)
            // A disconnect() that lands between the fallback decision and the retry
            // cancels the job before the new session starts; settle on Disconnected.
            if (retryWithRaw) _connection.value = X11ConnectionState.Disconnected
        }
    }

    fun disconnect() {
        // If a button is still held (e.g. leaving mid-drag-lock), tell the server
        // to release it BEFORE the session/socket is torn down, otherwise the
        // guest X server keeps the button held forever. This button-up is queued
        // on the serialized writer; the socket close (in connect()'s finally) is
        // queued after it, so the up flushes before the socket goes away.
        if (heldButtons != 0) {
            val c = cursor.value
            submitRfb { VncClient.sendPointer(it, c.x, c.y, 0) }
        }
        heldButtons = 0
        sessionJob?.cancel()
        sessionJob = null
        // Coroutine cancellation can't interrupt the blocking native socket read
        // in connect()'s read loop, so on an idle desktop (no framebuffer updates
        // arriving to unblock readFully) the read parks forever and the finally
        // that closes the socket, stops audio, and resets state never runs —
        // leaking the socket, its IO thread, and the audio stream on every screen
        // exit. Force-close the socket so the read throws and the finally runs.
        // Queued on the serialized writer AFTER the button-up above so that release
        // still flushes first; this is the same path connect()'s finally uses.
        // onDispose() calls disconnect() while the ViewModel scope is still alive
        // (onCleared runs later), so this executes.
        rfbSocket?.let { sock -> viewModelScope.launch(rfbDispatcher) { runCatching { sock.close() } } }
    }

    @Volatile private var lastViewportW = 0
    @Volatile private var lastViewportH = 0

    fun requestResolution(viewportW: Int, viewportH: Int) {
        lastViewportW = viewportW
        lastViewportH = viewportH
        val s = x11Settings.value
        val t = ResolutionPolicy.target(s, viewportW, viewportH)
        desiredW = t.w; desiredH = t.h
        submitRfb { VncClient.requestDesktopSize(it, screenId, t.w, t.h) }
    }

    fun setResolutionMode(m: ResolutionMode) {
        viewModelScope.launch { settings.setX11ResolutionMode(m.name) }
        val explicit = x11Settings.value.copy(resolutionMode = m)
        reapplyResolution(explicit)
    }

    fun setPreset(p: ResolutionPreset) {
        viewModelScope.launch { settings.setX11Preset(p.name) }
        val explicit = x11Settings.value.copy(resolutionMode = ResolutionMode.PRESET, preset = p)
        reapplyResolution(explicit)
    }

    fun setCustom(w: Int, h: Int) {
        // Clamp to a sane desktop max that also stays within the 16-bit field
        // requestDesktopSize truncates to, so a value entered in the sheet can
        // never wrap (e.g. 70000 -> 4464). Lower bound 1 avoids a 0-sized desktop.
        val cw = w.coerceIn(1, MAX_RESOLUTION)
        val ch = h.coerceIn(1, MAX_RESOLUTION)
        viewModelScope.launch { settings.setX11Custom(cw, ch) }
        val explicit = x11Settings.value.copy(resolutionMode = ResolutionMode.CUSTOM, customW = cw, customH = ch)
        reapplyResolution(explicit)
    }

    fun setRotation(r: RotationLock) {
        viewModelScope.launch { settings.setX11Rotation(r.name) }
    }

    fun setTouchMode(m: TouchMode) {
        viewModelScope.launch { settings.setX11TouchMode(m.name) }
    }

    fun setTrackpadSensitivity(v: Float) {
        viewModelScope.launch { settings.setX11TrackpadSensitivity(v) }
    }

    fun setTrackpadAccel(v: Boolean) {
        viewModelScope.launch { settings.setX11TrackpadAccel(v) }
    }

    fun setShowExtraKeys(v: Boolean) {
        viewModelScope.launch { settings.setX11ShowExtraKeys(v) }
    }

    fun setFullscreenDefault(v: Boolean) {
        viewModelScope.launch { settings.setX11Fullscreen(v) }
    }

    fun setDpi(v: Int) {
        viewModelScope.launch { settings.setX11Dpi(v) }
    }

    fun setRenderScale(v: Int) {
        viewModelScope.launch { settings.setX11RenderScale(v) }
        val explicit = x11Settings.value.copy(renderScale = v)
        reapplyResolution(explicit)
    }

    private fun reapplyResolution(explicit: X11Settings) {
        if (lastViewportW <= 0) return
        val t = ResolutionPolicy.target(explicit, lastViewportW, lastViewportH)
        desiredW = t.w; desiredH = t.h
        submitRfb { VncClient.requestDesktopSize(it, screenId, t.w, t.h) }
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        submitRfb { VncClient.sendPointer(it, x, y, buttonMask) }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        submitRfb { VncClient.sendKey(it, keysym, down) }
    }

    @Volatile private var heldButtons = 0

    fun moveTo(x: Int, y: Int) {
        cursor.value = android.graphics.Point(x.coerceIn(0, fbW - 1), y.coerceIn(0, fbH - 1))
        sendPointer(cursor.value.x, cursor.value.y, heldButtons)
    }

    fun press(button: Int) {
        heldButtons = heldButtons or button
        sendPointer(cursor.value.x, cursor.value.y, heldButtons)
    }

    fun release(button: Int) {
        heldButtons = heldButtons and button.inv()
        sendPointer(cursor.value.x, cursor.value.y, heldButtons)
    }

    fun click(button: Int) { press(button); release(button) }
    /** Physical-mouse update: absolute position + the full button mask in one event.
     *  Mouse is authoritative for the button mask (mask=0 on release must clear bits).
     *  A simultaneous touch drag-lock + physical mouse is a rare mixed-input edge left
     *  as-is; OR-ing the mask here would make mouse buttons un-releasable. */
    fun mouseUpdate(x: Int, y: Int, mask: Int) {
        val nx = x.coerceIn(0, fbW - 1); val ny = y.coerceIn(0, fbH - 1)
        cursor.value = android.graphics.Point(nx, ny)
        heldButtons = mask
        sendPointer(nx, ny, heldButtons)
    }

    fun scroll(up: Boolean, ticks: Int = 1) {
        val b = if (up) VncClient.BTN_WHEEL_UP else VncClient.BTN_WHEEL_DOWN
        repeat(ticks) {
            sendPointer(cursor.value.x, cursor.value.y, heldButtons or b)
            sendPointer(cursor.value.x, cursor.value.y, heldButtons)
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        // Sane desktop ceiling; also <= 0xFFFF so a custom value never wraps the
        // 16-bit width/height fields of the SetDesktopSize wire message.
        const val MAX_RESOLUTION = 7680
    }
}

/**
 * Debug-only (BuildConfig.DEBUG) stats for the once-per-second X11Stats log
 * assembled in X11SurfaceRenderer: bytes read off the RFB socket, framebuffer
 * updates applied, time spent copying damaged rows + updating the damage
 * tracker, and time from a FramebufferUpdate message's first byte to its
 * full decode (rx = transfer + decode). Written from the single-threaded
 * read loop in [X11ViewModel.connect], read and reset from the render thread
 * via [snapshotAndReset]; [lock] only guards this snapshot, never fbLock.
 */
private class X11DebugStats {
    private val lock = Any()
    private var bytes = 0L
    private var updates = 0L
    private var copyNanos = 0L
    private var rxNanos = 0L
    // Effective encoding for the current session (set at each session start from
    // x11-debug.conf and the Raw fallback, debug builds only); read cross-thread
    // by the renderer.
    @Volatile var encodingName = "raw"

    fun addBytes(n: Int) {
        synchronized(lock) { bytes += n }
    }

    fun recordRx(elapsedNanos: Long) {
        synchronized(lock) { rxNanos += elapsedNanos }
    }

    fun recordUpdate(copyElapsedNanos: Long) {
        synchronized(lock) {
            updates++
            copyNanos += copyElapsedNanos
        }
    }

    fun snapshotAndReset(): RfbDebugSnapshot = synchronized(lock) {
        val s = RfbDebugSnapshot(bytes, updates, copyNanos, rxNanos, encodingName)
        bytes = 0L; updates = 0L; copyNanos = 0L; rxNanos = 0L
        s
    }
}

/**
 * Debug-only (BuildConfig.DEBUG) reader for `filesDir/x11-debug.conf`: lets a
 * developer flip the advertised SetEncodings list and the present mode
 * without a rebuild. Read once per connect(), only when BuildConfig.DEBUG;
 * release builds never call [read]. Format is `key=value` lines; a
 * missing/unreadable file or an unrecognized value falls back to the default
 * for that key.
 */
private object X11DebugConfig {
    class Config(val wantZrle: Boolean, val encodingName: String, val presentHw: Boolean)

    fun read(context: Context): Config {
        var encodingName = "raw"
        var presentName = "sw"
        runCatching {
            val f = File(context.filesDir, "x11-debug.conf")
            if (f.isFile) {
                f.forEachLine { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        when (parts[0].trim()) {
                            "encoding" -> encodingName = parts[1].trim()
                            "present" -> presentName = parts[1].trim()
                        }
                    }
                }
            }
        }
        val zrle = encodingName == "zrle"
        return Config(
            wantZrle = zrle,
            encodingName = if (zrle) "zrle" else "raw",
            presentHw = presentName == "hw",
        )
    }
}

/**
 * Debug-only (BuildConfig.DEBUG) counting wrapper around the RFB socket's
 * InputStream, feeding [X11ViewModel.debugSnapshotAndReset] for the
 * once-per-second X11Stats log. Never constructed in a release build.
 */
private class CountingInputStream(inp: InputStream, private val onBytes: (Int) -> Unit) : FilterInputStream(inp) {
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) onBytes(1)
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) onBytes(n)
        return n
    }
}
