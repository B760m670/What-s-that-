package com.whatsthat.linux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the always-on service back after a reboot.
 *
 * "Keep running, and do not drop out" cannot stop at the next restart — a phone
 * used as a relay will reboot for an update sooner or later, usually at night
 * with nobody there to open the app. Android grants a foreground-service start
 * from BOOT_COMPLETED precisely for work like this, so the relay comes back by
 * itself.
 *
 * Only the relay. A desktop session is not restored: nobody is looking at it,
 * it is the expensive half, and starting one unasked would spend the device's
 * memory on a window no one opened.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (!LinuxEnvironment(context).serviceEnabled) return
                // The container state is in app storage, which is readable only
                // after the user has unlocked the device once; the service's own
                // checks cope with that, and a failed start is retried whenever
                // the app is next opened.
                LinuxSessionService.send(context, LinuxSessionService.ACTION_BOOT)
            }
        }
    }
}
