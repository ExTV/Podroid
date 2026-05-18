/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Reads raw signed-16-bit-LE stereo PCM from PulseAudio's
 * module-simple-protocol-tcp source and pumps it into AudioTrack.
 * Auto-reconnects on socket drop with 1 s backoff.
 */
package com.excp.podroid.x11

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

class AudioStreamer(private val host: String = "127.0.0.1") {

    companion object {
        private const val TAG = "AudioStreamer"
        // 16-bit stereo PCM = 4 bytes / frame; 4096 is a multiple of 4 so
        // readFully(buf, 0, BUF_BYTES) always yields whole frames. Mis-
        // aligned reads (raw `read()` returning e.g. 4093 bytes) feed
        // AudioTrack a half-frame, after which every subsequent sample is
        // shifted by 1–3 bytes — the audible result was the clicking the
        // user reported in Firefox video playback.
        private const val BUF_BYTES = 4096
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        val track = buildTrack()
        track.play()
        try {
            while (coroutineContext.isActive) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, X11Constants.AUDIO_PORT), 2000)
                        // Disable Nagle — audio frames are tiny and latency-
                        // sensitive; coalescing them adds jitter that AudioTrack
                        // resamples to fill, producing audible artefacts.
                        s.tcpNoDelay = true
                        val din = DataInputStream(s.getInputStream())
                        val buf = ByteArray(BUF_BYTES)
                        while (coroutineContext.isActive) {
                            // readFully → exactly BUF_BYTES (= integer frames).
                            // Throws EOFException on socket close → catch outer
                            // reconnects.
                            din.readFully(buf, 0, BUF_BYTES)
                            track.write(buf, 0, BUF_BYTES)
                        }
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "audio reconnect: ${e.message}")
                    delay(1000)
                }
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            X11Constants.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Target ~100 ms — 4410 frames @ 44.1 kHz * 2 channels * 2 bytes/sample
        // ≈ 17.6 KB. Floor at minBuf so AudioTrack never refuses to build.
        // Previously we used minBuf * 4 which gave ~170 ms one-way latency —
        // audible lag on click feedback in X11.
        val targetBuf = (X11Constants.AUDIO_SAMPLE_RATE / 10 *
            X11Constants.AUDIO_CHANNELS * 2).coerceAtLeast(minBuf)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(X11Constants.AUDIO_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(targetBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(1.0f) }
    }
}
