package com.chilusoft.mobiwall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * VPN-based firewall service. Establishes a local VPN.
 *
 * In app-blocking-only mode, only the packages of blocked apps are allowed to use the VPN,
 * so their traffic is intercepted and dropped while all other apps bypass the tunnel.
 *
 * In domain/IP blocking or connection-monitoring mode, all traffic is routed through the VPN
 * so PacketTunnel can filter DNS/UDP and collect statistics. TCP is currently not forwarded
 * in that mode (see PacketTunnel).
 */
class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private var running = false
    private var connectionStatsCollector: ConnectionStatsCollector? = null

    private var lastTransport: Int? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val channelId = "mobiwall_firewall_channel"
    private val notificationId = 1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Firewall Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager = getSystemService(ConnectivityManager::class.java)
        }
    }

    override fun onDestroy() {
        instance = null
        connectionStatsCollector = null
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (running) return
        val blockedUids = FirewallHelper.getBlockedUidsForCurrentNetwork(this)
        val blockedDomains = FirewallHelper.getBlockedDomainsForCurrentNetwork(this)
        val blockedIps = FirewallHelper.getBlockedIpsForCurrentNetwork(this)
        val monitoringEnabled = FirewallHelper.getConnectionMonitoringEnabled(this)
        val useDomainIpBlock = blockedDomains.isNotEmpty() || blockedIps.isNotEmpty() || monitoringEnabled

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MobiWall Firewall")
            .setContentText(
                if (useDomainIpBlock) "Blocking apps, domains & IPs"
                else "Blocking selected apps from network access"
            )
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        startForeground(notificationId, notification)
        try {
            var useDefaultRoute = true
            val builder = Builder()
                .setSession("MobiWall")
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .setBlocking(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (!useDomainIpBlock) {
                    val installed = packageManager.getInstalledApplications(0)
                    val blockedPackages = mutableListOf<String>()
                    for (app in installed) {
                        if (app.uid == android.os.Process.myUid()) continue
                        if (blockedUids.contains(app.uid.toString())) {
                            try {
                                builder.addAllowedApplication(app.packageName)
                                blockedPackages.add(app.packageName)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to add allowed (blocked) app ${app.packageName}", e)
                            }
                        }
                    }
                    if (blockedPackages.isEmpty()) {
                        // No apps are blocked. Route a non-routable TEST-NET block so the VPN
                        // is technically active but does not capture real traffic, and disallow
                        // MobiWall itself so the firewall UI keeps working.
                        useDefaultRoute = false
                        try {
                            builder.addDisallowedApplication(packageName)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to disallow self when no blocked apps", e)
                        }
                    }
                    Log.i(TAG, "App-blocking mode: ${blockedPackages.size} package(s) routed through VPN (defaultRoute=$useDefaultRoute)")
                }
            }
            if (useDefaultRoute) {
                builder.addRoute("0.0.0.0", 0)
            } else {
                builder.addRoute("192.0.2.0", 24)
            }
            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                running = true
                connectionStatsCollector = if (useDomainIpBlock) ConnectionStatsCollector() else null
                updateLastTransport()
                registerNetworkCallback()
                tunnelThread = thread(name = "FirewallTunnel") {
                    if (useDomainIpBlock) {
                        PacketTunnel.run(
                            vpnFd = vpnInterface!!.fileDescriptor,
                            vpnService = this,
                            blockedIps = blockedIps,
                            blockedDomains = blockedDomains,
                            isRunning = { running },
                            connectionStatsCollector = connectionStatsCollector,
                        )
                    } else {
                        runTunnel()
                    }
                }
            } else {
                stopForeground(true)
            }
        } catch (e: Exception) {
            stopForeground(true)
        }
    }

    private fun transportFromNetwork(network: Network?): Int? {
        if (network == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val cm = connectivityManager ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
            else -> null
        }
    }

    private fun updateLastTransport() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            lastTransport = transportFromNetwork(connectivityManager?.activeNetwork)
        }
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!running) return
                val transport = transportFromNetwork(network)
                if (transport != null && transport != lastTransport) {
                    lastTransport = transport
                    stopVpn()
                    startVpn()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } catch (_: Exception) { }
    }

    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                } catch (_: Exception) { }
            }
        }
        networkCallback = null
    }

    private fun stopVpn() {
        unregisterNetworkCallback()
        lastTransport = null
        running = false
        connectionStatsCollector = null
        tunnelThread?.interrupt()
        tunnelThread = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) { }
        vpnInterface = null
        stopForeground(true)
    }

    private fun runTunnel() {
        val vpn = vpnInterface ?: return
        val input = FileInputStream(vpn.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)
        while (running) {
            try {
                buffer.clear()
                input.channel.read(buffer)
            } catch (e: Exception) {
                if (running) break
            }
        }
    }

    companion object {
        private const val TAG = "FirewallVpnService"
        @Volatile
        private var instance: FirewallVpnService? = null
        fun getActiveConnectionsSnapshot(): List<Map<String, Any>> =
            instance?.connectionStatsCollector?.getSnapshot() ?: emptyList()
        fun isRunning(): Boolean = instance?.running == true
        const val ACTION_START = "com.chilusoft.mobiwall.START"
        const val ACTION_STOP = "com.chilusoft.mobiwall.STOP"
        const val PREFS_NAME = "mobiwall_firewall"
        const val BLOCKED_UIDS = "blocked_uids"
        const val BLOCKED_UIDS_WIFI = "blocked_uids_wifi"
        const val BLOCKED_UIDS_MOBILE = "blocked_uids_mobile"
        const val BLOCKED_DOMAINS = "blocked_domains"
        const val BLOCKED_IPS = "blocked_ips"
        const val BLOCKED_DOMAINS_WIFI = "blocked_domains_wifi"
        const val BLOCKED_DOMAINS_MOBILE = "blocked_domains_mobile"
        const val BLOCKED_IPS_WIFI = "blocked_ips_wifi"
        const val BLOCKED_IPS_MOBILE = "blocked_ips_mobile"
        const val CONNECTION_MONITORING_ENABLED = "connection_monitoring_enabled"
    }
}
