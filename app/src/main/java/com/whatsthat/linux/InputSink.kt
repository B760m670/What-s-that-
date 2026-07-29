package com.whatsthat.linux

/**
 * Where the desktop view sends pointer and key input, independent of how the
 * pixels arrive. RFB carries input on the same socket as pixels (VncClient); the
 * framebuffer backend has no such channel and drives the X server directly
 * (XTestInput). The canvas view only needs these two operations, so it takes an
 * InputSink and does not care which backend is behind it.
 *
 * Keys are X keysyms (e.g. 0xFF0D Return), matching what the view already emits.
 */
interface InputSink {
    /** buttonMask bit0 = left, bit1 = middle, bit2 = right. */
    fun sendPointer(buttonMask: Int, x: Int, y: Int)
    fun sendKey(keysym: Int, down: Boolean)
}
