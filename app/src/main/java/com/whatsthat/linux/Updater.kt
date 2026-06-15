package com.whatsthat.linux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app self-updater for the sideloaded APK. CI publishes the newest build to a
 * rolling GitHub Release (`latest-apk`) with the APK and a `version.txt` holding
 * the commit it was built from. On launch we compare that to our own
 * [BuildConfig.GIT_SHA]; if they differ we download the APK and hand it to the
 * system installer (the user confirms — Android won't silently update sideloads).
 *
 * Native code can't be hot-patched over the air, so this is the right model: one
 * tap to update instead of hunting for the EAS link.
 */
object Updater {
    private const val OWNER_REPO = "B760m670/What-s-that-"
    private const val TAG = "latest-apk"
    private const val BASE = "https://github.com/$OWNER_REPO/releases/download/$TAG"
    private const val VERSION_URL = "$BASE/version.txt"
    private const val APK_URL = "$BASE/whats-that-linux.apk"

    /** The commit SHA of the latest published build, or null if unreachable. */
    fun latestSha(): String? = runCatching {
        openFollowingRedirects(VERSION_URL).inputStream.bufferedReader().use {
            it.readText().trim().takeIf { s -> s.isNotEmpty() }
        }
    }.getOrNull()

    fun isUpdateAvailable(): Boolean {
        if (BuildConfig.GIT_SHA == "dev") return false      // local/dev build — don't nag
        val latest = latestSha() ?: return false
        return !latest.equals(BuildConfig.GIT_SHA, ignoreCase = true)
    }

    /** Download the APK into the FileProvider-shared dir. Returns the file or null. */
    fun downloadApk(context: Context, onLog: (String) -> Unit): File? = runCatching {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val apk = File(dir, "update.apk")
        onLog("Downloading update ...")
        val conn = openFollowingRedirects(APK_URL)
        try {
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                FileOutputStream(apk).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct >= lastPct + 20) { onLog("update $pct%"); lastPct = pct }
                        }
                    }
                }
            }
        } finally { conn.disconnect() }
        apk
    }.getOrElse { onLog("Update download failed: ${it.message}"); null }

    /** Launch the system package installer for [apk]. */
    fun install(context: Context, apk: File) {
        // Android 8+: the app must be allowed to install unknown apps first.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openFollowingRedirects(spec: String): HttpURLConnection {
        var url = URL(spec)
        repeat(6) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "WhatsThatLinux")
            }
            when (conn.responseCode) {
                in 300..399 -> {
                    val loc = conn.getHeaderField("Location") ?: error("redirect without Location")
                    conn.disconnect(); url = URL(url, loc)
                }
                in 200..299 -> return conn
                else -> error("HTTP ${conn.responseCode}")
            }
        }
        error("too many redirects")
    }
}
