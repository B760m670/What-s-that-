package com.whatsthat.linux

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A small, self-contained RFB (VNC) client — no third-party library, no paid
 * service. It speaks just enough of the protocol to render our local TigerVNC
 * desktop: RFB 3.8 handshake with the "None" security type (we only ever
 * connect to 127.0.0.1), 32-bit true-colour pixels, and Raw / CopyRect /
 * DesktopSize encodings. Bandwidth is irrelevant since the link is loopback.
 *
 * Networking runs on its own thread; [onFrame] is invoked with the shared
 * framebuffer bitmap after each update (post it to the UI thread to draw).
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val onConnected: (width: Int, height: Int, bitmap: Bitmap) -> Unit,
    private val onFrame: () -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    // All post-handshake writes go through one writer thread, so callers (incl.
    // the UI thread handling touches) never touch the socket directly — that
    // would throw NetworkOnMainThreadException.
    private val writeQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    private var width = 0
    private var height = 0
    @Volatile var bitmap: Bitmap? = null
        private set
    private var thread: Thread? = null

    // Lightweight diagnostics surfaced in the viewer's status overlay.
    val isRunning: Boolean get() = running
    @Volatile var framesReceived = 0; private set
    @Volatile var pointerSent = 0; private set
    @Volatile var keySent = 0; private set
    @Volatile var lastError: String? = null; private set
    val fbWidth: Int get() = width
    val fbHeight: Int get() = height

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "vnc-client").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    private fun run() {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 8000)
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream()))
            output = DataOutputStream(s.getOutputStream())

            handshake()
            startWriter()
            requestUpdate(incremental = false)
            startRefreshNudge()
            messageLoop()
        } catch (e: Exception) {
            if (running) { lastError = e.message; onError(e.message ?: "VNC connection error") }
        } finally {
            running = false
            runCatching { socket?.close() }
        }
    }

    /**
     * Insurance against a frozen display: periodically ask for an incremental
     * update. Normally the request we send after each frame keeps exactly one
     * outstanding, but if that ever gets consumed with no follow-up, this keeps
     * the desktop (incl. cursor moves from our pointer events) flowing.
     */
    /** The single thread that actually writes queued messages to the socket. */
    private fun startWriter() {
        Thread {
            try {
                while (running) {
                    val msg = writeQueue.take()
                    output.write(msg)
                    output.flush()
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                lastError = "writer:${e.javaClass.simpleName}:${e.message}"
            }
        }.apply { isDaemon = true; name = "vnc-writer"; start() }
    }

    private fun startRefreshNudge() {
        Thread {
            while (running) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                runCatching { requestUpdate(incremental = true) }
            }
        }.apply { isDaemon = true; name = "vnc-refresh"; start() }
    }

    // --- handshake -----------------------------------------------------------

    private fun handshake() {
        val serverVersion = ByteArray(12)
        input.readFully(serverVersion)
        directWrite { output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)) }

        val numTypes = input.readUnsignedByte()
        if (numTypes == 0) throw RuntimeException("Server refused: ${readString()}")
        val types = ByteArray(numTypes).also { input.readFully(it) }
        if (types.none { it.toInt() == SEC_NONE }) throw RuntimeException("Server requires authentication")
        directWrite { output.writeByte(SEC_NONE) }

        val securityResult = input.readInt()
        if (securityResult != 0) throw RuntimeException("Handshake failed: ${readString()}")

        directWrite { output.writeByte(1) }      // ClientInit: shared = true

        width = input.readUnsignedShort()         // ServerInit
        height = input.readUnsignedShort()
        input.skipBytes(16)                       // server pixel format (we set our own)
        val nameLen = input.readInt()
        input.skipBytes(nameLen)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        setPixelFormat()
        setEncodings()
        onConnected(width, height, bmp)
    }

    private fun setPixelFormat() = directWrite {
        output.writeByte(0); output.writeByte(0); output.writeByte(0); output.writeByte(0) // type + pad
        output.writeByte(32)   // bits-per-pixel
        output.writeByte(24)   // depth
        output.writeByte(0)    // big-endian = false
        output.writeByte(1)    // true-colour
        output.writeShort(255); output.writeShort(255); output.writeShort(255) // r/g/b max
        output.writeByte(16); output.writeByte(8); output.writeByte(0)         // r/g/b shift
        output.writeByte(0); output.writeByte(0); output.writeByte(0)          // padding
    }

    private fun setEncodings() = directWrite {
        output.writeByte(2); output.writeByte(0)  // type + pad
        output.writeShort(3)
        output.writeInt(ENC_RAW)
        output.writeInt(ENC_COPYRECT)
        output.writeInt(ENC_DESKTOP_SIZE)
    }

    /** Enqueued (off the caller's thread) so it's safe to call from anywhere. */
    private fun requestUpdate(incremental: Boolean) {
        val w = width; val h = height
        writeQueue.offer(byteArrayOf(
            3, (if (incremental) 1 else 0).toByte(), 0, 0, 0, 0,
            (w ushr 8).toByte(), w.toByte(), (h ushr 8).toByte(), h.toByte(),
        ))
    }

    // --- server message loop -------------------------------------------------

    private fun messageLoop() {
        while (running) {
            when (input.readUnsignedByte()) {
                0 -> handleFramebufferUpdate()
                1 -> handleColourMap()
                2 -> { /* Bell — ignore */ }
                3 -> { input.skipBytes(3); val n = input.readInt(); input.skipBytes(n) } // ServerCutText
                else -> { /* unknown — best effort continue */ }
            }
        }
    }

    private fun handleFramebufferUpdate() {
        input.skipBytes(1)
        val rects = input.readUnsignedShort()
        repeat(rects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val enc = input.readInt()) {
                ENC_RAW -> readRaw(x, y, w, h)
                ENC_COPYRECT -> readCopyRect(x, y, w, h)
                ENC_DESKTOP_SIZE -> resize(w, h)
                else -> throw RuntimeException("Unsupported encoding $enc")
            }
        }
        framesReceived++
        onFrame()
        requestUpdate(incremental = true)
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val bmp = bitmap ?: return
        val buf = ByteArray(w * 4)
        val row = IntArray(w)
        for (yy in 0 until h) {
            input.readFully(buf)
            var p = 0
            for (xx in 0 until w) {
                val b = buf[p].toInt() and 0xFF
                val g = buf[p + 1].toInt() and 0xFF
                val r = buf[p + 2].toInt() and 0xFF
                row[xx] = -0x1000000 or (r shl 16) or (g shl 8) or b
                p += 4
            }
            if (y + yy < bmp.height) bmp.setPixels(row, 0, w, x, y + yy, minOf(w, bmp.width - x), 1)
        }
    }

    private fun readCopyRect(x: Int, y: Int, w: Int, h: Int) {
        val bmp = bitmap ?: return
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        val tmp = IntArray(w * h)
        bmp.getPixels(tmp, 0, w, srcX, srcY, w, h)
        bmp.setPixels(tmp, 0, w, x, y, w, h)
    }

    private fun resize(w: Int, h: Int) {
        width = w; height = h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        onConnected(w, h, bmp)
    }

    private fun handleColourMap() {
        input.skipBytes(3); input.readUnsignedShort()
        val n = input.readUnsignedShort()
        input.skipBytes(n * 6)
    }

    // --- client input --------------------------------------------------------

    /** buttonMask bit0 = left, bit1 = middle, bit2 = right. Safe from any thread. */
    fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        if (!running) return
        val cx = x.coerceIn(0, (width - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (height - 1).coerceAtLeast(0))
        writeQueue.offer(byteArrayOf(
            5, buttonMask.toByte(),
            (cx ushr 8).toByte(), cx.toByte(), (cy ushr 8).toByte(), cy.toByte(),
        ))
        pointerSent++
    }

    fun sendKey(keysym: Int, down: Boolean) {
        if (!running) return
        writeQueue.offer(byteArrayOf(
            4, (if (down) 1 else 0).toByte(), 0, 0,
            (keysym ushr 24).toByte(), (keysym ushr 16).toByte(),
            (keysym ushr 8).toByte(), keysym.toByte(),
        ))
        keySent++
    }

    /** Direct write — only used during the handshake, on the network thread. */
    private inline fun directWrite(block: () -> Unit) {
        block()
        output.flush()
    }

    private fun readString(): String {
        val len = input.readInt()
        return ByteArray(len).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }

    private companion object {
        const val SEC_NONE = 1
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_DESKTOP_SIZE = -223
    }
}
