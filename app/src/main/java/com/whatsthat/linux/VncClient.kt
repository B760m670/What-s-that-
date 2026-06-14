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
    private val writeLock = Any()

    private var width = 0
    private var height = 0
    @Volatile var bitmap: Bitmap? = null
        private set
    private var thread: Thread? = null

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
            requestUpdate(incremental = false)
            messageLoop()
        } catch (e: Exception) {
            if (running) onError(e.message ?: "VNC connection error")
        } finally {
            running = false
            runCatching { socket?.close() }
        }
    }

    // --- handshake -----------------------------------------------------------

    private fun handshake() {
        val serverVersion = ByteArray(12)
        input.readFully(serverVersion)
        write { output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)) }

        val numTypes = input.readUnsignedByte()
        if (numTypes == 0) throw RuntimeException("Server refused: ${readString()}")
        val types = ByteArray(numTypes).also { input.readFully(it) }
        if (types.none { it.toInt() == SEC_NONE }) throw RuntimeException("Server requires authentication")
        write { output.writeByte(SEC_NONE) }

        val securityResult = input.readInt()
        if (securityResult != 0) throw RuntimeException("Handshake failed: ${readString()}")

        write { output.writeByte(1) }            // ClientInit: shared = true

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

    private fun setPixelFormat() = write {
        output.writeByte(0); output.writeByte(0); output.writeByte(0); output.writeByte(0) // type + pad
        output.writeByte(32)   // bits-per-pixel
        output.writeByte(24)   // depth
        output.writeByte(0)    // big-endian = false
        output.writeByte(1)    // true-colour
        output.writeShort(255); output.writeShort(255); output.writeShort(255) // r/g/b max
        output.writeByte(16); output.writeByte(8); output.writeByte(0)         // r/g/b shift
        output.writeByte(0); output.writeByte(0); output.writeByte(0)          // padding
    }

    private fun setEncodings() = write {
        output.writeByte(2); output.writeByte(0)  // type + pad
        output.writeShort(3)
        output.writeInt(ENC_RAW)
        output.writeInt(ENC_COPYRECT)
        output.writeInt(ENC_DESKTOP_SIZE)
    }

    private fun requestUpdate(incremental: Boolean) = write {
        output.writeByte(3)
        output.writeByte(if (incremental) 1 else 0)
        output.writeShort(0); output.writeShort(0)
        output.writeShort(width); output.writeShort(height)
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

    /** buttonMask bit0 = left, bit1 = middle, bit2 = right. */
    fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        if (!running) return
        runCatching {
            write {
                output.writeByte(5)
                output.writeByte(buttonMask)
                output.writeShort(x.coerceIn(0, width - 1))
                output.writeShort(y.coerceIn(0, height - 1))
            }
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        if (!running) return
        runCatching {
            write {
                output.writeByte(4)
                output.writeByte(if (down) 1 else 0)
                output.writeByte(0); output.writeByte(0)
                output.writeInt(keysym)
            }
        }
    }

    private inline fun write(block: () -> Unit) {
        synchronized(writeLock) {
            block()
            output.flush()
        }
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
