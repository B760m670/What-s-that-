package com.whatsthat.linux

/** Package manager a distro uses for installing the desktop. */
enum class PkgManager { APT, APK }

/**
 * A Linux distribution the app can install side-by-side with others. Each one
 * lives in its own rootfs dir; the rootfs tarball is resolved per device arch.
 */
class Distro(
    val id: String,
    val name: String,
    val pkg: PkgManager,
    /** Rough compressed rootfs download size, MB (shown before install). */
    val approxDownloadMb: Int,
    /** Resolve the rootfs tarball URL. [http] fetches a URL's text (for index lookups). */
    val resolveUrl: (arch: String, http: (String) -> String) -> String,
    /** Optional SHA256SUMS URL for integrity verification. */
    val checksumUrl: ((arch: String) -> String)? = null,
    val experimental: Boolean = false,
    /** A Windows-apps (Box64 + Wine) environment built on this Linux base. */
    val wine: Boolean = false,
)

object Distros {

    /** Our OWN pre-built images (built in CI, published to the 'images' release).
     *  Every distro is a ready image now: download + run, no on-device apt, no
     *  upstream surprises, and nothing one distro does can break another. */
    private const val IMAGES =
        "https://github.com/B760m670/What-s-that-/releases/download/images"

    private fun image(file: String): (String, (String) -> String) -> String =
        { arch, _ ->
            if (arch == "aarch64") "$IMAGES/$file"
            else error("The ready-made images are arm64-only for now")
        }

    val all: List<Distro> = listOf(
        Distro(
            id = "wt-debian", name = "Debian 12 (XFCE)", pkg = PkgManager.APT, approxDownloadMb = 700,
            resolveUrl = image("whatsthat-debian-xfce-arm64.tar.xz"),
        ),
        Distro(
            id = "ubuntu", name = "Ubuntu 22.04 (XFCE)", pkg = PkgManager.APT, approxDownloadMb = 700,
            resolveUrl = image("whatsthat-ubuntu-xfce-arm64.tar.xz"),
        ),
        Distro(
            id = "windows", name = "Windows apps (Wine)", pkg = PkgManager.APT, approxDownloadMb = 500,
            resolveUrl = image("whatsthat-debian-wine-arm64.tar.xz"),
            experimental = true, wine = true,
        ),
    )

    fun byId(id: String): Distro = all.firstOrNull { it.id == id } ?: all.first()
}
