package com.chilusoft.mobiwall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Registers the firewall method channel as a Flutter plugin so it is not lost
 * (avoids MissingPluginException after hot restart / engine lifecycle).
 */
class FirewallPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var applicationContext: android.content.Context? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "com.chilusoft.mobiwall/firewall")
        channel!!.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        applicationContext = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    private fun context(): android.content.Context = activity ?: applicationContext!!

    override fun onMethodCall(call: MethodCall, result: Result) {
        val ctx = applicationContext ?: run {
            result.error("UNAVAILABLE", "Context not available", null)
            return
        }
        val act = activity
        when (call.method) {
            "getInstalledApps" -> {
                try {
                    result.success(FirewallHelper.getInstalledApps(ctx))
                } catch (e: Exception) {
                    result.error("ERROR", e.message, null)
                }
            }
            "isVpnPermissionGranted" -> result.success(FirewallHelper.isVpnPermissionGranted(ctx))
            "requestVpnPermission" -> {
                if (act == null) {
                    result.error("UNAVAILABLE", "Activity not available", null)
                    return@onMethodCall
                }
                val intent = android.net.VpnService.prepare(act)
                if (intent != null) {
                    result.success(false)
                    act.startActivityForResult(intent, VPN_REQUEST_CODE)
                } else {
                    result.success(true)
                }
            }
            "getBlockedUids" -> result.success(ArrayList(FirewallHelper.getBlockedUids(ctx)))
            "getBlockedUidsWifi" -> result.success(ArrayList(FirewallHelper.getBlockedUidsWifi(ctx)))
            "getBlockedUidsMobile" -> result.success(ArrayList(FirewallHelper.getBlockedUidsMobile(ctx)))
            "setBlockedUids" -> {
                @Suppress("UNCHECKED_CAST")
                val uids = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedUids(ctx, uids)
                result.success(null)
            }
            "setBlockedUidsWifi" -> {
                @Suppress("UNCHECKED_CAST")
                val uids = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedUidsWifi(ctx, uids)
                result.success(null)
            }
            "setBlockedUidsMobile" -> {
                @Suppress("UNCHECKED_CAST")
                val uids = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedUidsMobile(ctx, uids)
                result.success(null)
            }
            "startFirewall" -> {
                ctx.startService(Intent(ctx, FirewallVpnService::class.java).apply {
                    action = FirewallVpnService.ACTION_START
                })
                result.success(null)
            }
            "stopFirewall" -> {
                ctx.startService(Intent(ctx, FirewallVpnService::class.java).apply {
                    action = FirewallVpnService.ACTION_STOP
                })
                result.success(null)
            }
            "isFirewallRunning" -> result.success(FirewallVpnService.isRunning())
            "isIgnoringBatteryOptimizations" -> {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
                result.success(pm.isIgnoringBatteryOptimizations(ctx.packageName))
            }
            "requestBatteryOptimizationExemption" -> {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    result.success(true)
                } else {
                    if (act == null) {
                        result.error("UNAVAILABLE", "Activity not available", null)
                        return@onMethodCall
                    }
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                        }
                        act.startActivity(intent)
                        result.success(false)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }
            }
            "restartFirewall" -> {
                if (FirewallVpnService.isRunning()) {
                    ctx.startService(Intent(ctx, FirewallVpnService::class.java).apply {
                        action = FirewallVpnService.ACTION_STOP
                    })
                    ctx.startService(Intent(ctx, FirewallVpnService::class.java).apply {
                        action = FirewallVpnService.ACTION_START
                    })
                }
                result.success(null)
            }
            "getBlockedDomains" -> result.success(ArrayList(FirewallHelper.getBlockedDomains(ctx)))
            "setBlockedDomains" -> {
                @Suppress("UNCHECKED_CAST")
                val domains = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedDomains(ctx, domains)
                result.success(null)
            }
            "getBlockedDomainsWifi" -> result.success(ArrayList(FirewallHelper.getBlockedDomainsWifi(ctx)))
            "setBlockedDomainsWifi" -> {
                @Suppress("UNCHECKED_CAST")
                val domains = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedDomainsWifi(ctx, domains)
                result.success(null)
            }
            "getBlockedDomainsMobile" -> result.success(ArrayList(FirewallHelper.getBlockedDomainsMobile(ctx)))
            "setBlockedDomainsMobile" -> {
                @Suppress("UNCHECKED_CAST")
                val domains = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedDomainsMobile(ctx, domains)
                result.success(null)
            }
            "getBlockedIps" -> result.success(ArrayList(FirewallHelper.getBlockedIps(ctx)))
            "setBlockedIps" -> {
                @Suppress("UNCHECKED_CAST")
                val ips = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedIps(ctx, ips)
                result.success(null)
            }
            "getBlockedIpsWifi" -> result.success(ArrayList(FirewallHelper.getBlockedIpsWifi(ctx)))
            "setBlockedIpsWifi" -> {
                @Suppress("UNCHECKED_CAST")
                val ips = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedIpsWifi(ctx, ips)
                result.success(null)
            }
            "getBlockedIpsMobile" -> result.success(ArrayList(FirewallHelper.getBlockedIpsMobile(ctx)))
            "setBlockedIpsMobile" -> {
                @Suppress("UNCHECKED_CAST")
                val ips = (call.arguments as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                FirewallHelper.setBlockedIpsMobile(ctx, ips)
                result.success(null)
            }
            "getConnectionMonitoringEnabled" -> result.success(FirewallHelper.getConnectionMonitoringEnabled(ctx))
            "setConnectionMonitoringEnabled" -> {
                val enabled = call.arguments as? Boolean ?: false
                FirewallHelper.setConnectionMonitoringEnabled(ctx, enabled)
                result.success(null)
            }
            "getActiveConnections" -> {
                try {
                    val list = FirewallVpnService.getActiveConnectionsSnapshot().map { m ->
                        val host = (m["host"] as? String) ?: ""
                        val display = (m["display"] as? String) ?: host
                        val bytesSent = (m["bytesSent"] as? Number)?.toLong() ?: 0L
                        val bytesReceived = (m["bytesReceived"] as? Number)?.toLong() ?: 0L
                        val firstSeenMs = (m["firstSeenMs"] as? Number)?.toLong() ?: 0L
                        mapOf<String, Any>(
                            "host" to host,
                            "display" to display,
                            "bytesSent" to bytesSent,
                            "bytesReceived" to bytesReceived,
                            "firstSeenMs" to firstSeenMs,
                        )
                    }
                    result.success(list)
                } catch (e: Exception) {
                    result.error("ERROR", e.message, null)
                }
            }
            else -> result.notImplemented()
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }
}
