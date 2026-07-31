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
    /** Resolve the rootfs tarball URL. [http] fetches a URL's text (for index lookups). */
    val resolveUrl: (arch: String, http: (String) -> String) -> String,
    /** Optional SHA256SUMS URL for integrity verification. */
    val checksumUrl: ((arch: String) -> String)? = null,
    val experimental: Boolean = false,
    /** Vector mark shown in the list and on the main screen. */
    val iconRes: Int = R.drawable.ic_distro_ubuntu,
    /** Accent tint for this distro's card, icon and chips. */
    val accentRes: Int = R.color.accent_generic,
    /** One line under the name: what this distro is for. */
    val tagline: String = "",
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

    val all: List<Distro> = listOf(
        Distro(
            id = "ubuntu", name = "Ubuntu 22.04", pkg = PkgManager.APT,
            resolveUrl = { arch, _ ->
                "https://cloud-images.ubuntu.com/releases/22.04/release/" +
                    "ubuntu-22.04-server-cloudimg-${debArch(arch)}-root.tar.xz"
            },
            checksumUrl = { "https://cloud-images.ubuntu.com/releases/22.04/release/SHA256SUMS" },
            iconRes = R.drawable.ic_distro_ubuntu,
            accentRes = R.color.accent_ubuntu,
            tagline = "Long-term support. The most familiar starting point.",
        ),
        Distro(
            id = "debian", name = "Debian 12 (Bookworm)", pkg = PkgManager.APT,
            resolveUrl = lxc("debian", "bookworm"),
            iconRes = R.drawable.ic_distro_debian,
            accentRes = R.color.accent_debian,
            tagline = "Stable and lean. Ships Firefox ESR as a real package.",
        ),
        // Our OWN pre-built image: Box64 + Wine + a light Openbox desktop baked
        // in (built in CI). EXPERIMENTAL — on a Mali GPU only software rendering
        // is available, so expect simple/old Windows programs, not games.
        Distro(
            id = "windows", name = "Windows apps (Wine) — experimental", pkg = PkgManager.APT,
            resolveUrl = { arch, _ ->
                if (arch == "aarch64")
                    "https://github.com/B760m670/What-s-that-/releases/download/images/whatsthat-debian-wine-arm64.tar.xz"
                else error("The Wine image is arm64-only for now")
            },
            experimental = true,
            iconRes = R.drawable.ic_distro_wine,
            accentRes = R.color.accent_wine,
            tagline = "Debian with Box64 + Wine baked in, for Windows programs.",
        ),
    )

    fun byId(id: String): Distro = all.firstOrNull { it.id == id } ?: all.first()
}
