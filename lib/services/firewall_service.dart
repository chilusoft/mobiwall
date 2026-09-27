import 'package:flutter/services.dart';

/// Platform channel to Android firewall VPN and app list.
class FirewallService {
  static const _channel = MethodChannel('com.chilusoft.mobiwall/firewall');

  /// Returns list of installed apps (excluding this app). Each map has:
  /// packageName, name, uid.
  static Future<List<Map<String, dynamic>>> getInstalledApps() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getInstalledApps');
    if (list == null) return [];
    return list
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
  }

  static Future<bool> isVpnPermissionGranted() async {
    return (await _channel.invokeMethod<bool>('isVpnPermissionGranted')) ?? false;
  }

  /// Returns false if user must grant permission (activity will be started).
  static Future<bool> requestVpnPermission() async {
    return (await _channel.invokeMethod<bool>('requestVpnPermission')) ?? false;
  }

  static Future<List<String>> getBlockedUids() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedUids');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<List<String>> getBlockedUidsWifi() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedUidsWifi');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<List<String>> getBlockedUidsMobile() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedUidsMobile');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<void> setBlockedUids(List<String> uids) async {
    await _channel.invokeMethod<void>('setBlockedUids', uids);
  }

  static Future<void> setBlockedUidsWifi(List<String> uids) async {
    await _channel.invokeMethod<void>('setBlockedUidsWifi', uids);
  }

  static Future<void> setBlockedUidsMobile(List<String> uids) async {
    await _channel.invokeMethod<void>('setBlockedUidsMobile', uids);
  }

  static Future<void> startFirewall() async {
    await _channel.invokeMethod<void>('startFirewall');
  }

  static Future<void> stopFirewall() async {
    await _channel.invokeMethod<void>('stopFirewall');
  }

  static Future<bool> isFirewallRunning() async {
    return (await _channel.invokeMethod<bool>('isFirewallRunning')) ?? false;
  }

  /// Restarts the firewall so updated block lists (e.g. from Active Connections) take effect.
  static Future<void> restartFirewall() async {
    await _channel.invokeMethod<void>('restartFirewall');
  }

  // Blocked domains and IPs (incoming + outgoing)
  static Future<List<String>> getBlockedDomains() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedDomains');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<void> setBlockedDomains(List<String> domains) async {
    await _channel.invokeMethod<void>('setBlockedDomains', domains);
  }

  static Future<List<String>> getBlockedIps() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedIps');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<void> setBlockedIps(List<String> ips) async {
    await _channel.invokeMethod<void>('setBlockedIps', ips);
  }

  // Per-network (WiFi / mobile) block lists for domains and IPs
  static Future<List<String>> getBlockedDomainsWifi() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedDomainsWifi');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<void> setBlockedDomainsWifi(List<String> domains) async {
    await _channel.invokeMethod<void>('setBlockedDomainsWifi', domains);
  }

  static Future<List<String>> getBlockedDomainsMobile() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedDomainsMobile');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<void> setBlockedDomainsMobile(List<String> domains) async {
    await _channel.invokeMethod<void>('setBlockedDomainsMobile', domains);
  }

  static Future<List<String>> getBlockedIpsWifi() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedIpsWifi');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<void> setBlockedIpsWifi(List<String> ips) async {
    await _channel.invokeMethod<void>('setBlockedIpsWifi', ips);
  }

  static Future<List<String>> getBlockedIpsMobile() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getBlockedIpsMobile');
    if (list == null) return [];
    return list.map((e) => e.toString()).toList();
  }

  static Future<void> setBlockedIpsMobile(List<String> ips) async {
    await _channel.invokeMethod<void>('setBlockedIpsMobile', ips);
  }

  /// When true, the tunnel runs to collect connection stats even with no domain/IP blocks.
  static Future<bool> getConnectionMonitoringEnabled() async {
    return (await _channel.invokeMethod<bool>('getConnectionMonitoringEnabled')) ?? false;
  }

  static Future<void> setConnectionMonitoringEnabled(bool enabled) async {
    await _channel.invokeMethod<void>('setConnectionMonitoringEnabled', enabled);
  }

  /// Currently active connections (when firewall is on and tunnel is running).
  /// Each map has: host, display, bytesSent, bytesReceived, firstSeenMs.
  static Future<List<Map<Object?, Object?>>> getActiveConnections() async {
    final list = await _channel.invokeMethod<List<dynamic>>('getActiveConnections');
    if (list == null) return [];
    return list
        .map((e) => (e as Map<Object?, Object?>))
        .toList();
  }

  /// Returns true if the app is already ignoring battery optimizations.
  static Future<bool> isIgnoringBatteryOptimizations() async {
    return (await _channel.invokeMethod<bool>('isIgnoringBatteryOptimizations')) ?? false;
  }

  /// Requests battery optimization exemption.
  /// Returns true if already exempt, false if the request dialog was launched.
  static Future<bool> requestBatteryOptimizationExemption() async {
    return (await _channel.invokeMethod<bool>('requestBatteryOptimizationExemption')) ?? false;
  }
}
