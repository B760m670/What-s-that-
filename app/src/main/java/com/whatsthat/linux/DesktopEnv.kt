package com.whatsthat.linux

/**
 * A desktop environment that can be installed into any distro's rootfs.
 *
 * Deliberately NOT modelled as a kind of [Distro]. A distro is a rootfs you
 * download once (gigabytes); a desktop is a set of packages installed inside it.
 * Folding them together ("Ubuntu XFCE", "Ubuntu KDE") would mean re-downloading
 * the same rootfs per desktop, an ambiguous notion of "installed", and no way to
 * change your mind without reinstalling the system. Keeping the two axes
 * separate lets any desktop go on any distro, and lets a second one be added to
 * a rootfs that already has one.
 */
class DesktopEnv(
    val id: String,
    val name: String,
    /** The binary start-desktop.sh execs, and what proves the desktop is installed. */
    val sessionCmd: String,
    /** Debian/Ubuntu package list. */
    val packages: String,
    /** Shown in the picker when there is something the user should know first. */
    val note: String? = null,
)

object DesktopEnvs {

    /** XFCE and GNOME, on any distro. Nothing else — extra entries were noise. */
    val all: List<DesktopEnv> = listOf(
        DesktopEnv(
            id = "xfce", name = "XFCE", sessionCmd = "startxfce4",
            packages = "xfce4 xfce4-terminal xfce4-goodies",
            note = "The default. A complete desktop that runs acceptably here.",
        ),
        DesktopEnv(
            id = "gnome", name = "GNOME", sessionCmd = "gnome-session",
            // Deliberately not gnome-core: that pulls gdm3, a display manager
            // that cannot work without a real init. Just the session and shell.
            packages = "gnome-session gnome-shell gnome-terminal",
            note = "Large download. GNOME Shell is a compositing desktop, so it " +
                "depends on OpenGL — if it fails to start, the log says why and " +
                "switching back to XFCE needs no reinstall.",
        ),
    )

    const val DEFAULT_ID = "xfce"

    /** Falls through to the first entry, so an unknown stored id can never loop. */
    fun byId(id: String): DesktopEnv =
        all.firstOrNull { it.id == id } ?: all.first()
}
