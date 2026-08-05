package com.chilusoft.mobiwall

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream

object FirewallHelper {

    private const val ICON_SIZE = 64

    fun getInstalledApps(context: Context): List<Map<String, Any>> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        return apps
            .filter { it.packageName != context.packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { app ->
                val iconBase64 = drawableToBase64(pm.getApplicationIcon(app))
                mapOf(
                    "packageName" to app.packageName,
                    "name" to (app.loadLabel(pm).toString().ifBlank { app.packageName }),
                    "uid" to app.uid,
                    "icon" to (iconBase64 ?: "")
                )
            }
            .sortedBy { (it["name"] as String).lowercase() }
    }

    private fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp
                }
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, ICON_SIZE, ICON_SIZE, true)
            if (scaled != bitmap) bitmap.recycle()
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
            if (scaled != bitmap) scaled.recycle()
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun isVpnPermissionGranted(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    private fun migrateLegacyBlockedUids(context: Context) {
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = prefs.getStringSet(FirewallVpnService.BLOCKED_UIDS, null) ?: return
        if (legacy.isEmpty()) return
        prefs.edit()
            .putStringSet(FirewallVpnService.BLOCKED_UIDS_WIFI, legacy)
            .putStringSet(FirewallVpnService.BLOCKED_UIDS_MOBILE, legacy)
            .remove(FirewallVpnService.BLOCKED_UIDS)
            .apply()
    }

    fun getBlockedUidsWifi(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        val wifi = prefs.getStringSet(FirewallVpnService.BLOCKED_UIDS_WIFI, null)
        if (wifi == null && prefs.contains(FirewallVpnService.BLOCKED_UIDS)) {
            migrateLegacyBlockedUids(context)
            return prefs.getStringSet(FirewallVpnService.BLOCKED_UIDS_WIFI, emptySet()) ?: emptySet()
        }
        return wifi ?: emptySet()
    }

    fun getBlockedUidsMobile(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        val mobile = prefs.getStringSet(FirewallVpnService.BLOCKED_UIDS_MOBILE, null)
        if (mobile == null && prefs.contains(FirewallVpnService.BLOCKED_UIDS)) {
            migrateLegacyBlockedUids(context)
            return prefs.getStringSet(FirewallVpnService.BLOCKED_UIDS_MOBILE, emptySet()) ?: emptySet()
        }
        return mobile ?: emptySet()
    }

    fun setBlockedUidsWifi(context: Context, uids: Set<String>) {
        context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(FirewallVpnService.BLOCKED_UIDS_WIFI, uids)
            .apply()
    }

    fun setBlockedUidsMobile(context: Context, uids: Set<String>) {
        context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(FirewallVpnService.BLOCKED_UIDS_MOBILE, uids)
            .apply()
    }

    fun getBlockedUids(context: Context): Set<String> {
        return getBlockedUidsWifi(context) + getBlockedUidsMobile(context)
    }

    fun setBlockedUids(context: Context, uids: Set<String>) {
        setBlockedUidsWifi(context, uids)
        setBlockedUidsMobile(context, uids)
    }

    /** Returns UIDs to block based on current default network (WiFi vs cellular). */
    fun getBlockedUidsForCurrentNetwork(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return getBlockedUidsWifi(context) + getBlockedUidsMobile(context)
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return getBlockedUidsWifi(context) + getBlockedUidsMobile(context)
        val network = cm.activeNetwork ?: return getBlockedUidsWifi(context) + getBlockedUidsMobile(context)
        val caps = cm.getNetworkCapabilities(network) ?: return getBlockedUidsWifi(context) + getBlockedUidsMobile(context)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getBlockedUidsWifi(context)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getBlockedUidsMobile(context)
            else -> getBlockedUidsWifi(context) + getBlockedUidsMobile(context)
        }
    }

    // Blocked domains and IPs (incoming + outgoing), with optional WiFi/Mobile split
    private fun migrateLegacyBlockedDomainsAndIps(context: Context) {
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        val domains = prefs.getStringSet(FirewallVpnService.BLOCKED_DOMAINS, null)
        if (domains != null && !prefs.contains(FirewallVpnService.BLOCKED_DOMAINS_WIFI)) {
            edit.putStringSet(FirewallVpnService.BLOCKED_DOMAINS_WIFI, domains)
                .putStringSet(FirewallVpnService.BLOCKED_DOMAINS_MOBILE, domains)
        }
        val ips = prefs.getStringSet(FirewallVpnService.BLOCKED_IPS, null)
        if (ips != null && !prefs.contains(FirewallVpnService.BLOCKED_IPS_WIFI)) {
            edit.putStringSet(FirewallVpnService.BLOCKED_IPS_WIFI, ips)
                .putStringSet(FirewallVpnService.BLOCKED_IPS_MOBILE, ips)
        }
        if (domains != null || ips != null) edit.apply()
    }

    fun getBlockedDomainsWifi(context: Context): Set<String> {
        migrateLegacyBlockedDomainsAndIps(context)
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(FirewallVpnService.BLOCKED_DOMAINS_WIFI, null)
            ?: prefs.getStringSet(FirewallVpnService.BLOCKED_DOMAINS, null) ?: emptySet()
    }

    fun getBlockedDomainsMobile(context: Context): Set<String> {
        migrateLegacyBlockedDomainsAndIps(context)
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(FirewallVpnService.BLOCKED_DOMAINS_MOBILE, null)
            ?: prefs.getStringSet(FirewallVpnService.BLOCKED_DOMAINS, null) ?: emptySet()
    }

    fun setBlockedDomainsWifi(context: Context, domains: Set<String>) {
        context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(FirewallVpnService.BLOCKED_DOMAINS_WIFI, domains)
            .apply()
    }

    fun setBlockedDomainsMobile(context: Context, domains: Set<String>) {
        context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(FirewallVpnService.BLOCKED_DOMAINS_MOBILE, domains)
            .apply()
    }

    fun getBlockedIpsWifi(context: Context): Set<String> {
        migrateLegacyBlockedDomainsAndIps(context)
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(FirewallVpnService.BLOCKED_IPS_WIFI, null)
            ?: prefs.getStringSet(FirewallVpnService.BLOCKED_IPS, null) ?: emptySet()
    }

    fun getBlockedIpsMobile(context: Context): Set<String> {
        migrateLegacyBlockedDomainsAndIps(context)
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(FirewallVpnService.BLOCKED_IPS_MOBILE, null)
            ?: prefs.getStringSet(FirewallVpnService.BLOCKED_IPS, null) ?: emptySet()
    }

    fun setBlockedIpsWifi(context: Context, ips: Set<String>) {
        context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(FirewallVpnService.BLOCKED_IPS_WIFI, ips)
            .apply()
    }

    fun setBlockedIpsMobile(context: Context, ips: Set<String>) {
        context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(FirewallVpnService.BLOCKED_IPS_MOBILE, ips)
            .apply()
    }

    /** Returns domains to block based on current default network (WiFi vs cellular). */
    fun getBlockedDomainsForCurrentNetwork(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return getBlockedDomainsWifi(context) + getBlockedDomainsMobile(context)
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return getBlockedDomainsWifi(context) + getBlockedDomainsMobile(context)
        val network = cm.activeNetwork ?: return getBlockedDomainsWifi(context) + getBlockedDomainsMobile(context)
        val caps = cm.getNetworkCapabilities(network) ?: return getBlockedDomainsWifi(context) + getBlockedDomainsMobile(context)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getBlockedDomainsWifi(context)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getBlockedDomainsMobile(context)
            else -> getBlockedDomainsWifi(context) + getBlockedDomainsMobile(context)
        }
    }

    /** Returns IPs to block based on current default network (WiFi vs cellular). */
    fun getBlockedIpsForCurrentNetwork(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return getBlockedIpsWifi(context) + getBlockedIpsMobile(context)
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return getBlockedIpsWifi(context) + getBlockedIpsMobile(context)
        val network = cm.activeNetwork ?: return getBlockedIpsWifi(context) + getBlockedIpsMobile(context)
        val caps = cm.getNetworkCapabilities(network) ?: return getBlockedIpsWifi(context) + getBlockedIpsMobile(context)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getBlockedIpsWifi(context)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getBlockedIpsMobile(context)
            else -> getBlockedIpsWifi(context) + getBlockedIpsMobile(context)
        }
    }

    // Legacy: union of WiFi + Mobile (for UI that edits a single list)
    fun getBlockedDomains(context: Context): Set<String> =
        getBlockedDomainsWifi(context) + getBlockedDomainsMobile(context)

    fun setBlockedDomains(context: Context, domains: Set<String>) {
        setBlockedDomainsWifi(context, domains)
        setBlockedDomainsMobile(context, domains)
    }

    fun getBlockedIps(context: Context): Set<String> =
        getBlockedIpsWifi(context) + getBlockedIpsMobile(context)

    fun setBlockedIps(context: Context, ips: Set<String>) {
        setBlockedIpsWifi(context, ips)
        setBlockedIpsMobile(context, ips)
    }

    /** When true, the VPN runs the full tunnel (with empty block lists if needed) to collect connection stats. */
    fun getConnectionMonitoringEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(FirewallVpnService.CONNECTION_MONITORING_ENABLED, false)
    }

    fun setConnectionMonitoringEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FirewallVpnService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(FirewallVpnService.CONNECTION_MONITORING_ENABLED, enabled)
            .commit()
    }
}
