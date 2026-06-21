package com.whatsthat.linux

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plays the Linux container's audio on the phone. The container has no direct
 * access to the speaker, so PulseAudio there routes everything to a null sink
 * and streams that sink's raw PCM over a loopback TCP socket (set up by
 * start-desktop.sh). This reads the stream and writes it to an [AudioTrack].
 * Pure Kotlin — no native code.
 *
 * Stream format must match start-desktop.sh's module-simple-protocol-tcp:
 * signed 16-bit little-endian, 48 kHz, stereo.
 */
class AudioBridge(
    private val host: String = "127.0.0.1",
    private val port: Int = 4712,
    private val sampleRate: Int = 48000,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "audio-bridge").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { thread?.interrupt() }
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
    }

    private fun run() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        val bufSize = minBuf * 2
        // PulseAudio's TCP sink comes up a moment after the desktop — keep retrying.
        while (running) {
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), 3000)
                    sock.tcpNoDelay = true
                    val input = sock.getInputStream()
                    @Suppress("DEPRECATION")
                    val t = AudioTrack(
                        AudioManager.STREAM_MUSIC, sampleRate,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                        bufSize, AudioTrack.MODE_STREAM,
                    )
                    track = t
                    t.play()
                    val buf = ByteArray(bufSize)
                    while (running) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) t.write(buf, 0, n)
                    }
                    runCatching { t.pause(); t.flush(); t.release() }
                    track = null
                }
            } catch (e: Exception) {
                if (!running) break
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            }
        }
    }
}
