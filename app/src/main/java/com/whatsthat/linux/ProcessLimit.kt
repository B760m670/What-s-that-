package com.whatsthat.linux

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's cap on how many processes an app may run, and how to lift it
 * without the user having to understand any of this.
 *
 * Since Android 12 the system allows 32 processes outside its own management —
 * for all apps together — and SIGKILLs them when that is passed. A desktop
 * session is more than that on its own, which is why sessions here die with
 * signal 9 while gigabytes of memory sit free. No amount of making the desktop
 * lighter gets under the cap.
 *
 * Turning it off needs WRITE_SECURE_SETTINGS, which an ordinary app cannot
 * hold. The only route is adb — but adb does not need a computer: Android's
 * wireless debugging listens on the device itself, so the adb inside our own
 * container can talk to the phone it is running on, over loopback.
 *
 * What makes that bearable for a user is this file's other half. Wireless
 * debugging advertises itself over mDNS, so the ports can be discovered rather
 * than read off a screen and typed. All that is left for the user is the six
 * digits Android shows them.
 */
object ProcessLimit {

    const val SETTING = "settings_enable_monitor_phantom_procs"

    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"

    /**
     * Is the cap currently in force?
     *
     * Three answers, and the third matters: null means the device does not have
     * this setting at all, which is the case below Android 12 — there is
     * nothing to fix and nothing to offer.
     */
    fun isRestricted(context: Context): Boolean? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return null
        val value = runCatching {
            Settings.Global.getString(context.contentResolver, SETTING)
        }.getOrNull()
        // Unset means the default, and the default is "monitoring is on".
        return value == null || value == "1" || value.equals("true", ignoreCase = true)
    }

    /**
     * Try to turn it off directly. This succeeds only once the app has been
     * granted WRITE_SECURE_SETTINGS — which the adb run does on its way past,
     * precisely so that this is a one-time ordeal rather than a ritual.
     */
    fun disableDirectly(context: Context): Boolean = runCatching {
        Settings.Global.putString(context.contentResolver, SETTING, "false")
        isRestricted(context) == false
    }.getOrDefault(false)

    /** Restore the system default, for a user who wants it back. */
    fun restore(context: Context): Boolean = runCatching {
        Settings.Global.putString(context.contentResolver, SETTING, "true")
        true
    }.getOrDefault(false)

    /** Can the app write the setting itself, without going near adb again? */
    fun canWriteDirectly(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * The port wireless debugging is listening on, found by asking the network
     * rather than asking the user.
     *
     * The pairing service exists only while the "Pair device with pairing code"
     * dialog is open; the connection service appears once wireless debugging is
     * on at all. Returns 0 if nothing answered in time, and the caller then
     * falls back to asking for the number on screen.
     */
    fun findPairingPort(context: Context, timeoutMs: Long = 12_000) =
        discover(context, PAIRING_SERVICE, timeoutMs)

    fun findConnectPort(context: Context, timeoutMs: Long = 8_000) =
        discover(context, CONNECT_SERVICE, timeoutMs)

    private fun discover(context: Context, type: String, timeoutMs: Long): Int {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return 0
        val port = AtomicInteger(0)
        val done = CountDownLatch(1)

        // Resolving is a second round trip and may be refused while another
        // resolve is in flight, so one at a time and the first answer wins.
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) { }
            override fun onServiceResolved(info: NsdServiceInfo) {
                if (port.compareAndSet(0, info.port)) done.countDown()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(t: String?, e: Int) { }
            override fun onDiscoveryStarted(t: String?) { }
            override fun onDiscoveryStopped(t: String?) { }
            override fun onServiceLost(info: NsdServiceInfo?) { }
            override fun onServiceFound(info: NsdServiceInfo) {
                runCatching { nsd.resolveService(info, resolveListener) }
            }
        }

        return runCatching {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            port.get()
        }.getOrDefault(0)
    }
}
