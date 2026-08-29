package com.whatsthat.linux

/**
 * How the compositor talks to its clients.
 *
 * This is not cosmetic. GNOME deleted mutter's X11 backend in version 49 — not
 * deprecated, removed: `libmutter-18` contains no `MetaBackendX11*` class at
 * all, and `gnome-session` 50 ships no `xsessions/` directory. From that
 * release on, an X11 GNOME session cannot exist, and the only way to run GNOME
 * with a VNC transport is as a Wayland compositor.
 */
enum class SessionType(val id: String, val label: String) {
    /**
     * gnome-shell as an X11 compositing window manager, directly on the VNC
     * server. One composite fewer, so it is the faster of the two — and the one
     * that has no future upstream.
     */
    X11("x11", "X11"),

    /**
     * gnome-shell as a real Wayland compositor, nested inside a window on the
     * VNC server. Clients speak the Wayland protocol; X11 programs go through
     * Xwayland, which the shell starts itself. Costs one extra full composite,
     * every frame of it on the CPU.
     */
    WAYLAND("wayland", "Wayland"),
    ;

    companion object {
        fun byId(id: String?): SessionType = entries.firstOrNull { it.id == id } ?: X11
    }
}

/**
 * A desktop environment, which lives INSIDE a distro's rootfs as a set of
 * packages — deliberately not modelled as a kind of [Distro].
 *
 * A distro is a rootfs you download once and measure in gigabytes; a desktop is
 * an apt transaction inside one. Folding the two together ("Ubuntu GNOME",
 * "Debian GNOME") would mean downloading the same rootfs again per desktop, an
 * ambiguous notion of what "installed" means, and no way to change your mind
 * without starting over. Keeping the axes separate lets either desktop go into
 * either distro, lets both live in one rootfs at once, and makes switching back
 * a relaunch rather than a re-download.
 */
class DesktopEnv(
    val id: String,
    val name: String,
    /**
     * Path inside the rootfs whose existence proves this desktop is installed
     * *there*. The filesystem is the source of truth: a preference can say
     * GNOME while the rootfs the user just switched to has never seen it.
     */
    val probeBinary: String,
    val tagline: String,
    /** Whether this desktop can run as a Wayland compositor here. */
    val supportsWayland: Boolean,
    /** Rough on-disk cost, for saying so before a multi-hundred-megabyte install. */
    val weight: String,
) {
    companion object {
        val XFCE = DesktopEnv(
            id = "xfce",
            name = "XFCE",
            probeBinary = "usr/bin/startxfce4",
            tagline = "Light and predictable. The one that has always worked here.",
            supportsWayland = false,   // XFCE's Wayland port is not usable yet
            weight = "about 400 MB",
        )

        val GNOME = DesktopEnv(
            id = "gnome",
            name = "GNOME",
            probeBinary = "usr/bin/gnome-shell",
            tagline = "The full desktop: shell, Files, Settings. Heavy on a phone.",
            supportsWayland = true,
            weight = "1 GB and up",
        )

        val all: List<DesktopEnv> = listOf(XFCE, GNOME)

        fun byId(id: String?): DesktopEnv = all.firstOrNull { it.id == id } ?: XFCE
    }
}
