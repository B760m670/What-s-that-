package com.whatsthat.linux

import android.system.Os
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads, verifies and unpacks the Ubuntu rootfs entirely in-process.
 *
 * Android's app sandbox has no `curl`/`tar`/`xz`, so the old shell bootstrap
 * couldn't run on a device. This does it all with the JVM: HTTP via
 * [HttpURLConnection], SHA-256 via [MessageDigest], XZ via the pure-Java
 * tukaani lib, and a small built-in tar reader. No native binaries required.
 */
class RootfsInstaller(
    private val home: File,
    private val rootfs: File,
    private val arch: String,
    private val distro: Distro,
    private val onLog: (String) -> Unit,
) {
    /** Returns 0 on success, non-zero on failure. */
    fun install(): Int {
        if (File(rootfs, ".bootstrap-done").exists()) {
            onLog("[bootstrap] ${distro.name} already installed.")
            return 0
        }
        return try {
            rootfs.mkdirs()
            val tmp = File(home, "tmp").apply { mkdirs() }
            val tarball = File(tmp, "${distro.id}-rootfs.tar.xz")

            onLog("[bootstrap] Resolving ${distro.name} image…")
            val url = distro.resolveUrl(arch) { httpText(it) }
            val fname = url.substringAfterLast('/')
            onLog("[bootstrap] Downloading $fname …")
            download(url, tarball)

            val expected = distro.checksumUrl?.let { sumsUrl ->
                runCatching { fetchSha256(sumsUrl(arch), fname) }.getOrNull()
            }
            if (expected != null) {
                onLog("[bootstrap] Verifying integrity …")
                if (!sha256(tarball).equals(expected, ignoreCase = true)) {
                    onLog("[bootstrap] CHECKSUM MISMATCH — refusing to install.")
                    tarball.delete()
                    return 1
                }
                onLog("[bootstrap] Integrity verified (SHA256 OK).")
            } else {
                onLog("[bootstrap] WARNING: checksum unavailable — proceeding unverified.")
            }

            onLog("[bootstrap] Extracting rootfs (this runs once) …")
            extractTarXz(tarball)

            // Some images ship resolv.conf as a dangling symlink; write real files.
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/resolv.conf").apply { delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
            File(rootfs, "etc/hosts").apply { delete(); writeText("127.0.0.1 localhost\n") }

            tarball.delete()
            File(rootfs, ".bootstrap-done").createNewFile()
            onLog("[bootstrap] ${distro.name} ready.")
            0
        } catch (e: Exception) {
            onLog("[bootstrap] failed: ${e.message}")
            1
        }
    }

    // --- networking ----------------------------------------------------------

    /** Fetch a URL's body as text (used to resolve image-server directory indexes). */
    private fun httpText(spec: String): String =
        openFollowingRedirects(spec).inputStream.bufferedReader().use { it.readText() }

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
                    val loc = conn.getHeaderField("Location") ?: error("Redirect without Location")
                    conn.disconnect()
                    url = URL(url, loc)   // resolves relative + cross-host redirects
                }
                in 200..299 -> return conn
                else -> error("HTTP ${conn.responseCode} for $url")
            }
        }
        error("Too many redirects for $spec")
    }

    private fun download(spec: String, dest: File) {
        val conn = openFollowingRedirects(spec)
        try {
            val total = conn.contentLength.toLong()   // Int API works on minSdk 21 (rootfs < 2GB)
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct >= lastPct + 10) { onLog("[bootstrap] $pct%"); lastPct = pct }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchSha256(spec: String, fname: String): String? {
        openFollowingRedirects(spec).inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1].removePrefix("*") == fname) return parts[0]
            }
        }
        return null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // --- extraction (XZ + minimal USTAR/GNU tar) -----------------------------

    private fun extractTarXz(tarball: File) {
        // Detect compression by magic bytes: gzip (1f 8b) vs xz (everything else
        // we ship). Alpine's minirootfs is .tar.gz, Ubuntu/Debian are .tar.xz.
        val buffered = BufferedInputStream(tarball.inputStream())
        buffered.mark(4)
        val m0 = buffered.read(); val m1 = buffered.read()
        buffered.reset()
        val decompressed = if (m0 == 0x1f && m1 == 0x8b)
            java.util.zip.GZIPInputStream(buffered) else XZInputStream(buffered)
        decompressed.use { xz ->
            val header = ByteArray(512)
            var longName: String? = null
            var longLink: String? = null
            var paxName: String? = null
            var paxLink: String? = null
            while (true) {
                if (!readFully(xz, header)) break
                if (header.all { it.toInt() == 0 }) break          // end-of-archive

                var name = cString(header, 0, 100)
                val prefix = cString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                var link = cString(header, 157, 100)
                val mode = octal(header, 100, 8).toInt()

                // GNU long name/link: stored as the entry's data, applied next.
                if (type == 'L') { longName = readDataString(xz, size); continue }
                if (type == 'K') { longLink = readDataString(xz, size); continue }
                // PAX extended header ('x') / global ('g'): overrides for the
                // following entry (e.g. long 'path'/'linkpath').
                if (type == 'x') {
                    val (p, l) = parsePax(readDataString(xz, size))
                    if (p != null) paxName = p
                    if (l != null) paxLink = l
                    continue
                }
                if (type == 'g') { discard(xz, size); skipPadding(xz, size); continue }

                longName?.let { name = it; longName = null }
                longLink?.let { link = it; longLink = null }
                paxName?.let { name = it; paxName = null }
                paxLink?.let { link = it; paxLink = null }

                val isRegular = type == '0' || type == '\u0000'
                if (name.startsWith("dev/") || name == "dev") {
                    discard(xz, size)
                } else {
                    val out = File(rootfs, name)
                    when {
                        type == '5' -> out.mkdirs()                              // directory
                        type == '2' -> {                                         // symlink
                            out.parentFile?.mkdirs(); out.delete()
                            runCatching { Os.symlink(link, out.absolutePath) }
                        }
                        type == '1' -> {                                         // hardlink
                            out.parentFile?.mkdirs(); out.delete()
                            runCatching { File(rootfs, link).copyTo(out, overwrite = true) }
                        }
                        isRegular -> {                                           // regular file
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { copyN(xz, it, size) }
                            runCatching { Os.chmod(out.absolutePath, mode) }
                        }
                        else -> discard(xz, size)                                // fifo/char/block
                    }
                    // dir/symlink/hardlink carry no data (size 0); regular files
                    // were consumed by copyN, others by discard. Only the
                    // 512-byte padding remains.
                }
                skipPadding(xz, size)
            }
        }
    }

    /** Parse PAX records ("<len> key=value\n") for path / linkpath overrides. */
    private fun parsePax(text: String): Pair<String?, String?> {
        var path: String? = null
        var link: String? = null
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val afterLen = line.substringAfter(' ', line)
            val key = afterLen.substringBefore('=', "")
            val value = afterLen.substringAfter('=', "")
            when (key) {
                "path" -> path = value
                "linkpath" -> link = value
            }
        }
        return path to link
    }

    private fun readDataString(input: InputStream, size: Long): String {
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skipPadding(input, size)
        return String(data, Charsets.US_ASCII).trim('\u0000', ' ', '\n')
    }

    private fun copyN(input: InputStream, out: OutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    /** Read and throw away [size] bytes of entry data. */
    private fun discard(input: InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        var left = (512 - (size % 512)) % 512
        val buf = ByteArray(512)
        while (left > 0) {
            val n = input.read(buf, 0, left.toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.US_ASCII)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = cString(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }
}
