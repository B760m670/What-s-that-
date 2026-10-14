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

    /** Map our canonical arch to the name the image server uses. */
    private fun debArch(arch: String) = when (arch) {
        "aarch64" -> "arm64"
        "arm" -> "armhf"
        "x86_64" -> "amd64"
        else -> error("Unsupported arch: $arch")
    }

    /**
     * One distribution, deliberately.
     *
     * Three were offered, which meant every change had to be right on three
     * systems that name their packages, sessions and releases differently —
     * GNOME alone is `gnome.session` on Debian and `ubuntu.session` on Ubuntu —
     * and none of the three was getting the attention it needed. Ubuntu is the
     * one most people mean, so it is the one that gets made to work properly.
     *
     * Rootfs directories are keyed on the id, so a system installed earlier
     * under another id is still on disk; the Distributions screen lists those
     * separately so their gigabytes can be reclaimed.
     */
    val all: List<Distro> = listOf(
        // 24.04 rather than 22.04 for one reason: GNOME. Mutter's X11 backend
        // was removed in GNOME 49, so this is among the newest releases whose
        // GNOME can still be driven through an X server at all — 24.04 carries
        // GNOME 46, keeping both the X11 session and the nested-Wayland mode.
        //
        // Nothing here is used for display: the version shown in the app is
        // read back out of the installed rootfs, which cannot be wrong.
        Distro(
            id = "ubuntu", name = "Ubuntu", pkg = PkgManager.APT,
            resolveUrl = { arch, _ ->
                "https://cloud-images.ubuntu.com/releases/24.04/release/" +
                    "ubuntu-24.04-server-cloudimg-${debArch(arch)}-root.tar.xz"
            },
            checksumUrl = { "https://cloud-images.ubuntu.com/releases/24.04/release/SHA256SUMS" },
            iconRes = R.drawable.ic_distro_ubuntu,
            accentRes = R.color.accent_ubuntu,
            tagline = "Long-term support. The most familiar starting point.",
        ),
    )

    fun byId(id: String): Distro = all.firstOrNull { it.id == id } ?: all.first()
}
