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
    val weight: Weight,
    /** Shown in the picker when there is something the user should know first. */
    val note: String? = null,
) {
    enum class Weight(val label: String) {
        MINIMAL("very light"),
        LIGHT("light"),
        MEDIUM("medium"),
        HEAVY("heavy"),
    }
}

object DesktopEnvs {

    /**
     * Curated for what actually runs here: proot (no systemd, no real init), an
     * X session under VNC, and software or virgl rendering on a phone GPU. The
     * ordering is lightest-first, because on this stack that is the axis that
     * decides whether the desktop is pleasant.
     *
     * The heavy entries are labelled, not hidden. Guessing that something
     * "cannot work" and removing the option is worse than letting the device
     * answer: the packages show logind is only a Recommends for GNOME, so the
     * missing init is not the hard blocker it is often assumed to be.
     */
    val all: List<DesktopEnv> = listOf(
        DesktopEnv(
            id = "openbox", name = "Openbox", sessionCmd = "openbox-session",
            packages = "openbox obconf tint2 xterm pcmanfm",
            weight = DesktopEnv.Weight.MINIMAL,
            note = "Bare window manager with a panel and a file manager. The fastest option.",
        ),
        DesktopEnv(
            id = "lxde", name = "LXDE", sessionCmd = "startlxde",
            packages = "lxde-core lxterminal",
            weight = DesktopEnv.Weight.LIGHT,
        ),
        DesktopEnv(
            id = "lxqt", name = "LXQt", sessionCmd = "startlxqt",
            packages = "lxqt-core qterminal",
            weight = DesktopEnv.Weight.LIGHT,
        ),
        DesktopEnv(
            id = "xfce", name = "XFCE", sessionCmd = "startxfce4",
            packages = "xfce4 xfce4-terminal xfce4-goodies",
            weight = DesktopEnv.Weight.MEDIUM,
            note = "The default: a complete desktop that still runs acceptably here.",
        ),
        DesktopEnv(
            id = "mate", name = "MATE", sessionCmd = "mate-session",
            packages = "mate-desktop-environment-core mate-terminal",
            weight = DesktopEnv.Weight.MEDIUM,
        ),
        DesktopEnv(
            id = "kde", name = "KDE Plasma", sessionCmd = "startplasma-x11",
            packages = "kde-plasma-desktop konsole",
            weight = DesktopEnv.Weight.HEAVY,
            note = "Large download and noticeably slow on a phone under proot. " +
                "It does start, but expect to wait — pick a lighter one first.",
        ),
        // GNOME is the one people ask for, and it is genuinely the hardest here.
        // It is offered rather than hidden: in Debian, logind is only a
        // Recommends of gnome-session-bin, not a Depends, and libsystemd0 is
        // just the client library — so the absence of systemd as PID 1 does not
        // by itself rule it out. Whether it actually comes up on a given device
        // is a question only that device can answer, so say so plainly.
        DesktopEnv(
            id = "gnome", name = "GNOME", sessionCmd = "gnome-session",
            // Deliberately not gnome-core: that pulls gdm3, a display manager
            // that cannot work without a real init. Just the session and shell.
            packages = "gnome-session gnome-shell gnome-terminal",
            weight = DesktopEnv.Weight.HEAVY,
            note = "Experimental and the heaviest option — a large download, and " +
                "GNOME Shell is a compositing desktop, so it leans hard on the GPU " +
                "passthrough. It may fail to start on some devices; if it does, the " +
                "log says so and you can switch back without reinstalling anything.",
        ),
    )

    /** The lite build targets weak, old devices, so it starts from the bare WM. */
    val DEFAULT_ID: String =
        if (BuildConfig.DESKTOP_PROFILE == "lite") "openbox" else "xfce"

    // Resolve without recursing through DEFAULT_ID: an id that matches nothing
    // would otherwise loop forever if the default itself ever stopped matching.
    fun byId(id: String): DesktopEnv =
        all.firstOrNull { it.id == id }
            ?: all.firstOrNull { it.id == DEFAULT_ID }
            ?: all.first()

    /** Every session binary we know of — used to detect a desktop we did not install. */
    val allSessionCmds: List<String> = all.map { it.sessionCmd }
}
