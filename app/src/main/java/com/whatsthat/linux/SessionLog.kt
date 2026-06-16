package com.whatsthat.linux

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Persists the app's log lines to a file so the full log survives the desktop
 * viewer covering the screen, and can be shared (sent) in one tap.
 */
object SessionLog {

    private var file: File? = null

    /** Start a fresh log for this app launch. */
    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "session.log")
        runCatching { file?.writeText("") }
    }

    @Synchronized
    fun append(line: String) {
        runCatching { file?.appendText(line + "\n") }
    }

    fun text(): String = runCatching { file?.readText().orEmpty() }.getOrDefault("")

    /** Open the system share sheet with the full log (file + inline text). */
    fun share(context: Context) {
        val f = file ?: return
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text().takeLast(60_000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
