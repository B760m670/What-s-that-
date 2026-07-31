package com.whatsthat.linux

/**
 * One step of a long operation, with a byte count attached.
 *
 * The install is half an hour of downloading, hashing and unpacking behind a
 * single indeterminate bar, which looks exactly the same at 1% as at 99% — and
 * exactly the same as a hang. Reporting the counts the installer already has
 * turns that into something a person can read and decide about.
 *
 * [total] is 0 when the size genuinely isn't known (a server that sends no
 * Content-Length). The UI then falls back to an indeterminate bar for that
 * phase rather than inventing a figure.
 */
data class Progress(
    val phase: Phase,
    val done: Long = 0,
    val total: Long = 0,
    /** Average throughput since this phase began; 0 when not yet measurable. */
    val bytesPerSecond: Long = 0,
    /** What the step calls itself, when the units aren't bytes (see [DESKTOP]). */
    val label: String? = null,
) {
    enum class Phase {
        RESOLVE, DOWNLOAD, VERIFY, EXTRACT, FINALISE,

        /**
         * The desktop install: a fixed sequence of apt runs inside the
         * container. There is no byte count to be had across a dozen separate
         * apt invocations, but the script knows which of its steps it is on,
         * and that is a truthful bar rather than a spinner for what is the
         * second-longest wait in the app.
         */
        DESKTOP,
    }

    /** Whether [done]/[total] are bytes; false when they count steps. */
    val countsBytes: Boolean get() = phase != Phase.DESKTOP

    /** 0..100, or -1 when [total] is unknown. */
    val percent: Int
        get() = if (total > 0) (done * 100 / total).coerceIn(0, 100).toInt() else -1

    /** Seconds remaining at the current average rate, or -1 when not computable. */
    val secondsLeft: Long
        get() = if (total > 0 && bytesPerSecond > 0 && done < total) (total - done) / bytesPerSecond else -1
}
