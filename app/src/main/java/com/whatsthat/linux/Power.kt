package com.whatsthat.linux

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android is allowed to put this app to sleep, and how to ask it not to.
 *
 * It lives apart from [LinuxEnvironment] because it has nothing to do with the
 * container: it is about the host's power policy, and three different screens
 * need the same answer — the always-on service, which depends on it, and the
 * viewer, which has to name it as a suspect when a session dies with nothing
 * written in its log.
 */
object Power {

    /** True when nothing in the power policy stands between the app and the CPU. */
    fun isExempt(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    /**
     * Ask for the exemption. The direct request is not offered on every build of
     * Android — some vendors remove it — so the battery-settings list is the
     * fallback that always exists.
     *
     * Returns false only if neither screen could be opened, which is worth
     * saying out loud rather than leaving the user tapping a button that
     * appears to do nothing.
     */
    fun requestExemption(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return runCatching { activity.startActivity(direct); true }
            .recoverCatching { activity.startActivity(fallback); true }
            .getOrDefault(false)
    }
}
