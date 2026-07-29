package com.whatsthat.linux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Draws the desktop framebuffer scaled to fit, forwards touch as pointer events,
 * and feeds soft-keyboard / hardware-key input to the server as X keysyms.
 * Pure View + Canvas — no extra dependencies.
 */
class DesktopCanvasView(context: Context) : View(context) {

    var input: InputSink? = null

    private var frame: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var scale = 1f
    private var dx = 0f
    private var dy = 0f

    init {
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        recomputeMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = recomputeMatrix()

    private fun recomputeMatrix() {
        val bmp = frame ?: return
        if (width == 0 || height == 0) return
        scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        dx = (width - bmp.width * scale) / 2f
        dy = (height - bmp.height * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
    }

    override fun onDraw(canvas: Canvas) {
        frame?.let { canvas.drawBitmap(it, matrix, paint) }
    }

    // --- touch → pointer -----------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()   // so a hardware/USB keyboard's key events reach onKeyDown
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = frame ?: return false
        val fx = ((event.x - dx) / scale).toInt().coerceIn(0, bmp.width - 1)
        val fy = ((event.y - dy) / scale).toInt().coerceIn(0, bmp.height - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                // Move the pointer to the spot first (buttons up), then press —
                // some servers ignore a press that arrives without a prior move.
                input?.sendPointer(0, fx, fy)
                input?.sendPointer(1, fx, fy)
            }
            MotionEvent.ACTION_MOVE -> input?.sendPointer(1, fx, fy)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> input?.sendPointer(0, fx, fy)
        }
        return true
    }

    /**
     * A USB/Bluetooth mouse moves the pointer via hover events (no button down)
     * and scrolls via the scroll axis — neither of which arrive in onTouchEvent.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val bmp = frame ?: return false
        val fx = ((event.x - dx) / scale).toInt().coerceIn(0, bmp.width - 1)
        val fy = ((event.y - dy) / scale).toInt().coerceIn(0, bmp.height - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> input?.sendPointer(0, fx, fy)
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val wheel = if (v > 0) 1 shl 3 else 1 shl 4   // RFB button 4 up / 5 down
                input?.sendPointer(wheel, fx, fy)
                input?.sendPointer(0, fx, fy)
            }
            else -> return false
        }
        return true
    }

    /** Show the soft keyboard targeted at this view. */
    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL          // raw keys, no autocorrect
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.forEach { typeChar(it) }
                return true
            }
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                handleKeyEvent(event)
                return true
            }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { tapKeysym(KEYSYM_BACKSPACE) }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        handleKeyEvent(event); return true
    }

    private fun handleKeyEvent(event: android.view.KeyEvent) {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return
        val sym = keysymFor(event)
        if (sym != 0) tapKeysym(sym) else {
            val ch = event.unicodeChar
            if (ch != 0) typeChar(ch.toChar())
        }
    }

    private fun typeChar(c: Char) = tapKeysym(c.code)  // Latin-1 maps 1:1 to keysyms

    private fun tapKeysym(sym: Int) {
        input?.sendKey(sym, true)
        input?.sendKey(sym, false)
    }

    private fun keysymFor(e: android.view.KeyEvent): Int = when (e.keyCode) {
        android.view.KeyEvent.KEYCODE_ENTER -> KEYSYM_ENTER
        android.view.KeyEvent.KEYCODE_DEL -> KEYSYM_BACKSPACE
        android.view.KeyEvent.KEYCODE_TAB -> 0xFF09
        android.view.KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
        android.view.KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
        else -> 0
    }

    private companion object {
        const val KEYSYM_BACKSPACE = 0xFF08
        const val KEYSYM_ENTER = 0xFF0D
    }
}
