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
)

object Distros {

    /** Map our canonical arch to the Ubuntu/Debian/LXC image arch. */
    private fun debArch(arch: String) = when (arch) {
        "aarch64" -> "arm64"
        "arm" -> "armhf"
        "x86_64" -> "amd64"
        else -> error("Unsupported arch: $arch")
    }

    /**
     * Resolve the newest build on the LXC image server, which carries rootfs
     * tarballs for most distros in a uniform layout:
     *   images/<distro>/<release>/<arch>/default/<YYYYMMDD_HH:MM>/rootfs.tar.xz
     */
    private fun lxc(distro: String, release: String): (String, (String) -> String) -> String =
        { arch, http ->
            val base = "https://images.linuxcontainers.org/images/$distro/$release/${debArch(arch)}/default/"
            val listing = http(base)
            val ts = Regex("""\d{8}_\d{2}:\d{2}""").findAll(listing).map { it.value }.toList().maxOrNull()
                ?: error("No LXC build found for $distro/$release/${debArch(arch)}")
            "$base$ts/rootfs.tar.xz"
        }

    /**
     * Resolve the newest Alpine minirootfs from Alpine's own CDN (tiny, ~3 MB,
     * always available). The patch version changes, so read the directory.
     */
    private fun alpineMinirootfs(): (String, (String) -> String) -> String =
        { arch, http ->
            val a = when (arch) {
                "aarch64" -> "aarch64"
                "x86_64" -> "x86_64"
                "arm" -> "armv7"
                else -> error("Unsupported arch: $arch")
            }
            val base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$a/"
            val listing = http(base)
            val file = Regex("""alpine-minirootfs-[0-9][0-9.]*-$a\.tar\.gz""")
                .findAll(listing).map { it.value }.distinct().toList().maxOrNull()
                ?: error("No Alpine minirootfs found for $a")
            base + file
        }

    val all: List<Distro> = listOf(
        Distro(
            id = "ubuntu", name = "Ubuntu 22.04", pkg = PkgManager.APT, approxDownloadMb = 250,
            resolveUrl = { arch, _ ->
                "https://cloud-images.ubuntu.com/releases/22.04/release/" +
                    "ubuntu-22.04-server-cloudimg-${debArch(arch)}-root.tar.xz"
            },
            checksumUrl = { "https://cloud-images.ubuntu.com/releases/22.04/release/SHA256SUMS" },
        ),
        Distro(
            id = "debian", name = "Debian 12 (Bookworm)", pkg = PkgManager.APT, approxDownloadMb = 120,
            resolveUrl = lxc("debian", "bookworm"),
        ),
        Distro(
            id = "alpine", name = "Alpine (lightest)", pkg = PkgManager.APK, approxDownloadMb = 4,
            resolveUrl = alpineMinirootfs(), experimental = true,
        ),
    )

    fun byId(id: String): Distro = all.firstOrNull { it.id == id } ?: all.first()
}
