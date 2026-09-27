import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';

import '../models/active_connection.dart';
import '../services/firewall_service.dart';

enum ConnectionSortOption {
  dataSent,
  dataReceived,
  runningTime,
}

/// Screen that lists currently active connections (IPs/domains) with traffic stats,
/// sortable by data sent, data received, or running time, and toggles to block
/// each on WiFi or mobile data.
class ActiveConnectionsScreen extends StatefulWidget {
  const ActiveConnectionsScreen({super.key});

  @override
  State<ActiveConnectionsScreen> createState() => _ActiveConnectionsScreenState();
}

class _ActiveConnectionsScreenState extends State<ActiveConnectionsScreen> {
  List<ActiveConnection> _connections = [];
  Set<String> _blockedDomainsWifi = {};
  Set<String> _blockedDomainsMobile = {};
  Set<String> _blockedIpsWifi = {};
  Set<String> _blockedIpsMobile = {};
  bool _loading = true;
  bool _monitoringEnabled = false;
  bool _firewallOn = false;
  ConnectionSortOption _sortOption = ConnectionSortOption.dataSent;
  Timer? _refreshTimer;
  static const _vpnClientIp = '10.0.0.2';

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    if (!Platform.isAndroid) {
      setState(() {
        _loading = false;
        _connections = [];
      });
      return;
    }
    setState(() => _loading = true);
    try {
      final firewallOn = await FirewallService.isFirewallRunning();
      final monitoring = await FirewallService.getConnectionMonitoringEnabled();
      final connections = await FirewallService.getActiveConnections();
      final wifiDomains = await FirewallService.getBlockedDomainsWifi();
      final mobileDomains = await FirewallService.getBlockedDomainsMobile();
      final wifiIps = await FirewallService.getBlockedIpsWifi();
      final mobileIps = await FirewallService.getBlockedIpsMobile();
      setState(() {
        _firewallOn = firewallOn;
        _monitoringEnabled = monitoring;
        _connections = connections
            .map((m) => ActiveConnection.fromMap(m))
            .where((c) => c.host.isNotEmpty && c.host != _vpnClientIp)
            .toList();
        _blockedDomainsWifi = wifiDomains.toSet();
        _blockedDomainsMobile = mobileDomains.toSet();
        _blockedIpsWifi = wifiIps.toSet();
        _blockedIpsMobile = mobileIps.toSet();
        _loading = false;
      });
      _refreshTimer?.cancel();
      if (firewallOn && monitoring) {
        _refreshTimer = Timer.periodic(const Duration(seconds: 3), (_) => _load());
      }
    } catch (_) {
      setState(() => _loading = false);
    }
  }

  Future<void> _toggleMonitoring(bool enabled) async {
    if (!mounted) return;
    setState(() => _monitoringEnabled = enabled);
    try {
      await FirewallService.setConnectionMonitoringEnabled(enabled);
      final firewallOn = await FirewallService.isFirewallRunning();
      if (enabled && !firewallOn) {
        await FirewallService.startFirewall();
      } else if (firewallOn) {
        await FirewallService.restartFirewall();
      }
      if (mounted) {
        await _load();
      }
    } catch (e) {
      if (mounted) {
        setState(() => _monitoringEnabled = !enabled);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to update: $e')),
        );
      }
    }
  }

  List<ActiveConnection> get _sortedConnections {
    final list = List<ActiveConnection>.from(_connections);
    switch (_sortOption) {
      case ConnectionSortOption.dataSent:
        list.sort((a, b) => b.bytesSent.compareTo(a.bytesSent));
        break;
      case ConnectionSortOption.dataReceived:
        list.sort((a, b) => b.bytesReceived.compareTo(a.bytesReceived));
        break;
      case ConnectionSortOption.runningTime:
        list.sort((a, b) => b.firstSeenMs.compareTo(a.firstSeenMs));
        break;
    }
    return list;
  }

  static bool _looksLikeIp(String s) {
    final parts = s.split('.');
    if (parts.length != 4) return false;
    for (final p in parts) {
      final n = int.tryParse(p);
      if (n == null || n < 0 || n > 255) return false;
    }
    return true;
  }

  bool _isBlockedWifi(ActiveConnection c) {
    if (_looksLikeIp(c.host)) return _blockedIpsWifi.contains(c.host);
    return _blockedDomainsWifi.contains(c.display) ||
        _blockedDomainsWifi.any((d) => c.display == d || c.display.endsWith('.$d'));
  }

  bool _isBlockedMobile(ActiveConnection c) {
    if (_looksLikeIp(c.host)) return _blockedIpsMobile.contains(c.host);
    return _blockedDomainsMobile.contains(c.display) ||
        _blockedDomainsMobile.any((d) => c.display == d || c.display.endsWith('.$d'));
  }

  Future<void> _toggleBlockWifi(ActiveConnection c, bool block) async {
    final isIp = _looksLikeIp(c.host);
    if (isIp) {
      final next = Set<String>.from(_blockedIpsWifi);
      if (block) {
        next.add(c.host);
      } else {
        next.remove(c.host);
      }
      _blockedIpsWifi = next;
      await FirewallService.setBlockedIpsWifi(next.toList());
    } else {
      final domain = c.display.toLowerCase();
      final next = Set<String>.from(_blockedDomainsWifi);
      if (block) {
        next.add(domain);
      } else {
        next.remove(domain);
      }
      _blockedDomainsWifi = next;
      await FirewallService.setBlockedDomainsWifi(next.toList());
    }
    await FirewallService.restartFirewall();
    if (mounted) setState(() {});
  }

  Future<void> _toggleBlockMobile(ActiveConnection c, bool block) async {
    final isIp = _looksLikeIp(c.host);
    if (isIp) {
      final next = Set<String>.from(_blockedIpsMobile);
      if (block) {
        next.add(c.host);
      } else {
        next.remove(c.host);
      }
      _blockedIpsMobile = next;
      await FirewallService.setBlockedIpsMobile(next.toList());
    } else {
      final domain = c.display.toLowerCase();
      final next = Set<String>.from(_blockedDomainsMobile);
      if (block) {
        next.add(domain);
      } else {
        next.remove(domain);
      }
      _blockedDomainsMobile = next;
      await FirewallService.setBlockedDomainsMobile(next.toList());
    }
    await FirewallService.restartFirewall();
    if (mounted) setState(() {});
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  String _formatDuration(Duration d) {
    if (d.inDays > 0) return '${d.inDays}d ${d.inHours % 24}h';
    if (d.inHours > 0) return '${d.inHours}h ${d.inMinutes % 60}m';
    if (d.inMinutes > 0) return '${d.inMinutes}m ${d.inSeconds % 60}s';
    return '${d.inSeconds}s';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Active connections'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loading ? null : _load,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : !Platform.isAndroid
              ? const Center(child: Text('Only supported on Android.'))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Card(
                        margin: const EdgeInsets.fromLTRB(16, 12, 16, 8),
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Expanded(
                                    child: Text(
                                      'Enable connection monitoring',
                                      style: Theme.of(context).textTheme.titleSmall,
                                    ),
                                  ),
                                  Switch(
                                    value: _monitoringEnabled,
                                    onChanged: (v) => _toggleMonitoring(v),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 8),
                              Text(
                                _firewallOn && _monitoringEnabled
                                    ? 'Firewall is on and monitoring is enabled. Connections will appear below as you use the device.'
                                    : 'Turn on the firewall on the home screen, then enable monitoring here to see active outbound and inbound connections.',
                                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                                    ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: Row(
                          children: [
                            Text(
                              'Sort by:',
                              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                                  ),
                            ),
                            const SizedBox(width: 12),
                            DropdownButton<ConnectionSortOption>(
                              value: _sortOption,
                              underline: const SizedBox(),
                              items: const [
                                DropdownMenuItem(
                                  value: ConnectionSortOption.dataSent,
                                  child: Text('Data sent'),
                                ),
                                DropdownMenuItem(
                                  value: ConnectionSortOption.dataReceived,
                                  child: Text('Data received'),
                                ),
                                DropdownMenuItem(
                                  value: ConnectionSortOption.runningTime,
                                  child: Text('Running time'),
                                ),
                              ],
                              onChanged: (v) {
                                if (v != null) setState(() => _sortOption = v);
                              },
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 8),
                      Expanded(
                        child: _connections.isEmpty
                            ? Center(
                                child: Padding(
                                  padding: const EdgeInsets.all(24),
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Text(
                                        _firewallOn && _monitoringEnabled
                                            ? 'No connections yet. Use the device (browse, use apps) to generate traffic; the list will update every few seconds.'
                                            : 'Enable connection monitoring above and turn the firewall ON on the home screen. Then use the device to see active connections here.',
                                        textAlign: TextAlign.center,
                                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                                            ),
                                      ),
                                    ],
                                  ),
                                ),
                              )
                            : ListView.builder(
                                padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                                itemCount: _sortedConnections.length,
                                itemBuilder: (context, index) {
                                  final c = _sortedConnections[index];
                                  final blockWifi = _isBlockedWifi(c);
                                  final blockMobile = _isBlockedMobile(c);
                                  return Card(
                                    margin: const EdgeInsets.only(bottom: 8),
                                    child: Padding(
                                      padding: const EdgeInsets.symmetric(
                                        vertical: 12,
                                        horizontal: 16,
                                      ),
                                      child: Column(
                                        crossAxisAlignment: CrossAxisAlignment.start,
                                        children: [
                                          Text(
                                            c.display,
                                            style: Theme.of(context).textTheme.titleMedium,
                                            overflow: TextOverflow.ellipsis,
                                          ),
                                          if (c.display != c.host)
                                            Text(
                                              c.host,
                                              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                                                  ),
                                              overflow: TextOverflow.ellipsis,
                                            ),
                                          const SizedBox(height: 8),
                                          Row(
                                            children: [
                                              Icon(Icons.upload, size: 16, color: Theme.of(context).colorScheme.primary),
                                              const SizedBox(width: 4),
                                              Text(_formatBytes(c.bytesSent), style: Theme.of(context).textTheme.bodySmall),
                                              const SizedBox(width: 16),
                                              Icon(Icons.download, size: 16, color: Theme.of(context).colorScheme.secondary),
                                              const SizedBox(width: 4),
                                              Text(_formatBytes(c.bytesReceived), style: Theme.of(context).textTheme.bodySmall),
                                              const SizedBox(width: 16),
                                              Icon(Icons.schedule, size: 16, color: Theme.of(context).colorScheme.outline),
                                              const SizedBox(width: 4),
                                              Text(_formatDuration(c.runningTime), style: Theme.of(context).textTheme.bodySmall),
                                            ],
                                          ),
                                          const SizedBox(height: 12),
                                          Row(
                                            children: [
                                              Expanded(
                                                child: Row(
                                                  mainAxisSize: MainAxisSize.min,
                                                  children: [
                                                    Icon(Icons.wifi, size: 18, color: Theme.of(context).colorScheme.onSurfaceVariant),
                                                    const SizedBox(width: 4),
                                                    Text('WiFi', style: Theme.of(context).textTheme.bodySmall),
                                                    const SizedBox(width: 8),
                                                    Switch(
                                                      value: blockWifi,
                                                      onChanged: (v) => _toggleBlockWifi(c, v),
                                                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                                                    ),
                                                  ],
                                                ),
                                              ),
                                              Expanded(
                                                child: Row(
                                                  mainAxisSize: MainAxisSize.min,
                                                  children: [
                                                    Icon(Icons.signal_cellular_alt, size: 18, color: Theme.of(context).colorScheme.onSurfaceVariant),
                                                    const SizedBox(width: 4),
                                                    Text('Mobile', style: Theme.of(context).textTheme.bodySmall),
                                                    const SizedBox(width: 8),
                                                    Switch(
                                                      value: blockMobile,
                                                      onChanged: (v) => _toggleBlockMobile(c, v),
                                                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                                                    ),
                                                  ],
                                                ),
                                              ),
                                            ],
                                          ),
                                        ],
                                      ),
                                    ),
                                  );
                                },
                              ),
                      ),
                    ],
                  ),
                ),
    );
  }
}
