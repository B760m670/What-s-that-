package com.whatsthat.linux

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Input half of the framebuffer display backend.
 *
 * The RFB path gets input for free: the same socket that carries pixels also
 * carries pointer and key events, and TigerVNC translates keysyms for us. A
 * shared-framebuffer backend has no such channel — Xvfb only produces pixels —
 * so input has to go to the X server directly, as an X11 client using the XTEST
 * extension.
 *
 * Talks to the X server over its Unix socket. The guest creates it at
 * /tmp/.X11-unix/X<n>, which is the host's $WT_HOME/tmp/.X11-unix/X<n> thanks to
 * the bind in run-in-ubuntu.sh, so the app can reach it with no extra plumbing —
 * the same trick that carries the virgl socket.
 *
 * Not yet wired to the UI: this is the input half, the display half follows.
 * The protocol here was validated against a real X server before being written.
 */
class XTestInput(private val socketPath: String) {

    private var socket: LocalSocket? = null
    private var input: InputStream? = null
    private var output: BufferedOutputStream? = null

    private var rootWindow = 0
    private var xtestOpcode = -1
    private var minKeycode = 8
    private var maxKeycode = 255

    /** keysym -> [keycode, shiftColumn]. Layout-dependent, so read from the server. */
    private val keysymMap = HashMap<Int, IntArray>()

    /** Errors arrive asynchronously; surfaced for diagnostics rather than thrown. */
    @Volatile var errorCount = 0; private set
    @Volatile var lastErrorCode = 0; private set

    val isConnected: Boolean get() = socket != null

    fun connect(): Boolean = runCatching {
        val s = LocalSocket()
        s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        socket = s
        input = BufferedInputStream(s.inputStream)
        output = BufferedOutputStream(s.outputStream)
        handshake()
        xtestOpcode = queryExtension("XTEST")
        if (xtestOpcode < 0) error("server has no XTEST extension")
        loadKeyboardMapping()
        true
    }.getOrElse { close(); false }

    fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
        keysymMap.clear()
    }

    // --- wire helpers --------------------------------------------------------

    private fun le(cap: Int) = ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN)

    private fun send(b: ByteBuffer) {
        val out = output ?: error("not connected")
        out.write(b.array(), 0, b.position())
        out.flush()
    }

    private fun readFully(n: Int): ByteBuffer {
        val ins = input ?: error("not connected")
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = ins.read(buf, got, n - got)
            if (r < 0) throw EOFException("X server closed the connection")
            got += r
        }
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun pad4(n: Int) = (4 - (n % 4)) % 4

    // --- connection setup ----------------------------------------------------

    private fun handshake() {
        val req = le(12).apply {
            put(0x6c)                    // 'l' — little-endian
            put(0)
            putShort(11); putShort(0)    // protocol 11.0
            putShort(0); putShort(0)     // no auth: the socket is inside our sandbox
            putShort(0)
        }
        send(req)

        val head = readFully(8)
        val success = head.get().toInt() and 0xFF
        head.get(); head.short; head.short
        val addLen = head.short.toInt() and 0xFFFF
        if (success != 1) error("X handshake refused (code $success)")

        val d = readFully(addLen * 4)
        d.int; d.int; d.int; d.int              // release, id base, id mask, motion buffer
        val vendorLen = d.short.toInt() and 0xFFFF
        d.short                                  // max request length
        val numScreens = d.get().toInt() and 0xFF
        val numFormats = d.get().toInt() and 0xFF
        d.get(); d.get(); d.get(); d.get()      // byte/bit order, scanline unit/pad
        minKeycode = d.get().toInt() and 0xFF
        maxKeycode = d.get().toInt() and 0xFF
        d.int                                    // unused
        if (numScreens < 1) error("X server reported no screens")

        // Skip the vendor string and the pixmap FORMATs to reach the SCREENs.
        d.position(d.position() + vendorLen + pad4(vendorLen) + 8 * numFormats)
        rootWindow = d.int                       // a SCREEN starts with its root window
    }

    /**
     * Read until a real reply arrives.
     *
     * X errors and events are delivered asynchronously and are not answers to
     * the request just sent — XTestFakeInput expects no reply yet can still
     * produce an error. Taking the next 32 bytes as "our reply" desynchronises
     * the stream the moment anything goes wrong, so errors are counted and
     * skipped and events are skipped.
     */
    private fun reply(): ByteBuffer {
        while (true) {
            val r = readFully(32)
            when (r.get(0).toInt() and 0xFF) {
                0 -> { errorCount++; lastErrorCode = r.get(1).toInt() and 0xFF }
                1 -> {
                    val extra = r.getInt(4)
                    if (extra > 0) readFully(extra * 4)
                    r.position(1)
                    return r
                }
                // anything else is an event; we select none, but skip safely.
            }
        }
    }

    private fun queryExtension(name: String): Int {
        val n = name.toByteArray(Charsets.US_ASCII)
        val len = 2 + (n.size + pad4(n.size)) / 4
        val req = le(len * 4).apply {
            put(98); put(0); putShort(len.toShort())
            putShort(n.size.toShort()); putShort(0)
            put(n); repeat(pad4(n.size)) { put(0) }
        }
        send(req)
        val r = reply()
        r.position(8)
        val present = r.get().toInt() and 0xFF
        val major = r.get().toInt() and 0xFF
        return if (present == 1) major else -1
    }

    private fun loadKeyboardMapping() {
        val count = maxKeycode - minKeycode + 1
        val req = le(8).apply {
            put(101); put(0); putShort(2)
            put(minKeycode.toByte()); put(count.toByte()); putShort(0)
        }
        send(req)

        // This reply carries a payload, so it is read here rather than through
        // the 32-byte-only helper.
        var r: ByteBuffer
        while (true) {
            r = readFully(32)
            val type = r.get(0).toInt() and 0xFF
            if (type == 0) { errorCount++; lastErrorCode = r.get(1).toInt() and 0xFF; continue }
            if (type == 1) break
        }
        val perCode = r.get(1).toInt() and 0xFF
        val body = readFully(r.getInt(4) * 4)

        keysymMap.clear()
        for (i in 0 until count) {
            // Column 0 is unshifted, column 1 shifted; later columns are other
            // groups this does not drive.
            for (j in 0 until minOf(perCode, 2)) {
                val idx = i * perCode + j
                if ((idx + 1) * 4 > body.limit()) break
                val keysym = body.getInt(idx * 4)
                if (keysym != 0 && !keysymMap.containsKey(keysym)) {
                    keysymMap[keysym] = intArrayOf(minKeycode + i, j)
                }
            }
        }
    }

    // --- input ---------------------------------------------------------------

    private fun fakeInput(type: Int, detail: Int, x: Int, y: Int) {
        val req = le(36).apply {
            put(xtestOpcode.toByte()); put(2)      // X_XTestFakeInput
            putShort(9)                            // 36 bytes / 4
            put(type.toByte()); put(detail.toByte()); putShort(0)
            putInt(0)                              // delay
            putInt(rootWindow)
            putLong(0)
            putShort(x.toShort()); putShort(y.toShort())
            putLong(0)
        }
        send(req)
    }

    /** buttonMask bit0 = left, bit1 = middle, bit2 = right — matches VncClient. */
    fun sendPointer(buttonMask: Int, x: Int, y: Int): Boolean = runCatching {
        fakeInput(MOTION, 0, x, y)
        for (b in 0..2) {
            val down = (buttonMask shr b) and 1 == 1
            val wasDown = (buttons shr b) and 1 == 1
            if (down != wasDown) {
                fakeInput(if (down) BUTTON_PRESS else BUTTON_RELEASE, b + 1, x, y)
            }
        }
        buttons = buttonMask
        true
    }.getOrElse { false }

    private var buttons = 0

    /**
     * Send a keysym, adding Shift when the layout puts it in the shifted column.
     * Returns false for a keysym this layout cannot produce, so the caller can
     * report it rather than have keystrokes vanish silently.
     */
    fun sendKey(keysym: Int, down: Boolean): Boolean = runCatching {
        val m = keysymMap[keysym] ?: return false
        val shift = keysymMap[XK_SHIFT_L]?.get(0) ?: 0
        val needsShift = m[1] == 1 && shift != 0
        if (down) {
            if (needsShift) fakeInput(KEY_PRESS, shift, 0, 0)
            fakeInput(KEY_PRESS, m[0], 0, 0)
        } else {
            fakeInput(KEY_RELEASE, m[0], 0, 0)
            if (needsShift) fakeInput(KEY_RELEASE, shift, 0, 0)
        }
        true
    }.getOrElse { false }

    /** Round trip that flushes any pending async error to us. Also a liveness check. */
    fun pointerPosition(): IntArray? = runCatching {
        val req = le(8).apply { put(38); put(0); putShort(2); putInt(rootWindow) }
        send(req)
        val r = reply()
        r.position(16)
        intArrayOf(r.short.toInt(), r.short.toInt())
    }.getOrNull()

    private companion object {
        const val KEY_PRESS = 2
        const val KEY_RELEASE = 3
        const val BUTTON_PRESS = 4
        const val BUTTON_RELEASE = 5
        const val MOTION = 6
        const val XK_SHIFT_L = 0xFFE1
    }
}
