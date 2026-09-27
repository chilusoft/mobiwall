import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/app_info.dart';
import '../services/firewall_service.dart';
import '../services/theme_service.dart';
import 'active_connections_screen.dart';
import 'domain_ip_block_screen.dart';

enum AppSortOption {
  mostUsed,
  aToZ,
  zToA,
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  List<AppInfo> _apps = [];
  Set<String> _blockedUidsWifi = {};
  Set<String> _blockedUidsMobile = {};
  bool _firewallOn = false;
  bool _vpnPermissionGranted = false;
  bool _batteryOptIgnored = false;
  bool _loading = true;
  String? _error;

  final TextEditingController _searchController = TextEditingController();
  String _searchQuery = '';
  AppSortOption _sortOption = AppSortOption.mostUsed;
  Map<String, int> _usageCounts = {};
  static const _usagePrefsKey = 'mobiwall_app_usage';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    _searchController.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _checkVpnPermission();
  }

  Future<void> _load() async {
    if (!Platform.isAndroid) {
      setState(() {
        _loading = false;
        _error = 'MobiWall is only supported on Android.';
      });
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await _requestNotificationPermission();
      final granted = await FirewallService.isVpnPermissionGranted();
      final batteryOptIgnored = await FirewallService.isIgnoringBatteryOptimizations();
      final wifiUids = await FirewallService.getBlockedUidsWifi();
      final mobileUids = await FirewallService.getBlockedUidsMobile();
      final apps = await FirewallService.getInstalledApps();
      final prefs = await SharedPreferences.getInstance();
      final usageJson = prefs.getString(_usagePrefsKey);
      final usageCounts = _parseUsageCounts(usageJson);
      setState(() {
        _vpnPermissionGranted = granted;
        _batteryOptIgnored = batteryOptIgnored;
        _blockedUidsWifi = wifiUids.toSet();
        _blockedUidsMobile = mobileUids.toSet();
        _apps = apps.map((e) => AppInfo.fromMap(e)).toList();
        _usageCounts = usageCounts;
        _firewallOn = false;
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Map<String, int> _parseUsageCounts(String? json) {
    if (json == null || json.isEmpty) return {};
    final Map<String, int> decoded = {};
    try {
      for (final entry in json.split(',')) {
        final parts = entry.split(':');
        if (parts.length == 2) {
          final count = int.tryParse(parts[1]);
          if (count != null && count > 0) decoded[parts[0]] = count;
        }
      }
    } catch (_) {}
    return decoded;
  }

  Future<void> _saveUsageCounts() async {
    final prefs = await SharedPreferences.getInstance();
    final json = _usageCounts.entries
        .where((e) => e.value > 0)
        .map((e) => '${e.key}:${e.value}')
        .join(',');
    await prefs.setString(_usagePrefsKey, json);
  }

  void _recordAppUsage(String packageName) {
    _usageCounts[packageName] = (_usageCounts[packageName] ?? 0) + 1;
    _saveUsageCounts();
  }

  List<AppInfo> get _filteredAndSortedApps {
    final query = _searchQuery.trim().toLowerCase();
    var list = query.isEmpty
        ? List<AppInfo>.from(_apps)
        : _apps.where((a) {
            return a.name.toLowerCase().contains(query) ||
                a.packageName.toLowerCase().contains(query);
          }).toList();
    switch (_sortOption) {
      case AppSortOption.mostUsed:
        list.sort((a, b) {
          final countA = _usageCounts[a.packageName] ?? 0;
          final countB = _usageCounts[b.packageName] ?? 0;
          if (countB != countA) return countB.compareTo(countA);
          return a.name.toLowerCase().compareTo(b.name.toLowerCase());
        });
        break;
      case AppSortOption.aToZ:
        list.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
        break;
      case AppSortOption.zToA:
        list.sort((a, b) => b.name.toLowerCase().compareTo(a.name.toLowerCase()));
        break;
    }
    return list;
  }

  Future<void> _requestNotificationPermission() async {
    if (await Permission.notification.isDenied) {
      await Permission.notification.request();
    }
  }

  Future<void> _checkVpnPermission() async {
    if (!Platform.isAndroid) return;
    final granted = await FirewallService.isVpnPermissionGranted();
    if (mounted) setState(() => _vpnPermissionGranted = granted);
  }

  Future<void> _requestVpnPermission() async {
    final granted = await FirewallService.requestVpnPermission();
    if (granted) {
      setState(() => _vpnPermissionGranted = true);
      return;
    }
    await _checkVpnPermission();
  }

  Future<void> _requestBatteryOptimizationExemption() async {
    final result = await FirewallService.requestBatteryOptimizationExemption();
    if (result) {
      setState(() => _batteryOptIgnored = true);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Battery optimization is already disabled for MobiWall')),
        );
      }
    }
  }

  Future<void> _toggleFirewall(bool on) async {
    if (!_vpnPermissionGranted && on) {
      await _requestVpnPermission();
      if (!mounted) return;
      if (!_vpnPermissionGranted) return;
    }
    try {
      if (on) {
        await FirewallService.setBlockedUidsWifi(_blockedUidsWifi.toList());
        await FirewallService.setBlockedUidsMobile(_blockedUidsMobile.toList());
        await FirewallService.startFirewall();
      } else {
        await FirewallService.stopFirewall();
      }
      if (mounted) setState(() => _firewallOn = on);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error: $e')),
        );
      }
    }
  }

  Future<void> _toggleBlockWifi(AppInfo app, bool block) async {
    _recordAppUsage(app.packageName);
    final newSet = Set<String>.from(_blockedUidsWifi);
    if (block) newSet.add(app.uidString); else newSet.remove(app.uidString);
    _blockedUidsWifi = newSet;
    setState(() {});
    try {
      await FirewallService.setBlockedUidsWifi(_blockedUidsWifi.toList());
      if (_firewallOn) {
        await FirewallService.stopFirewall();
        await FirewallService.startFirewall();
      }
    } catch (_) {}
  }

  Future<void> _toggleBlockMobile(AppInfo app, bool block) async {
    _recordAppUsage(app.packageName);
    final newSet = Set<String>.from(_blockedUidsMobile);
    if (block) newSet.add(app.uidString); else newSet.remove(app.uidString);
    _blockedUidsMobile = newSet;
    setState(() {});
    try {
      await FirewallService.setBlockedUidsMobile(_blockedUidsMobile.toList());
      if (_firewallOn) {
        await FirewallService.stopFirewall();
        await FirewallService.startFirewall();
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid) {
      return Scaffold(
        appBar: AppBar(title: const Text('MobiWall')),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(
              _error ?? 'This app runs on Android only.',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('MobiWall Firewall'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          ValueListenableBuilder<ThemeMode>(
            valueListenable: ThemeService.themeNotifier,
            builder: (context, themeMode, _) {
              final isDark = themeMode == ThemeMode.dark;
              return IconButton(
                icon: Icon(isDark ? Icons.light_mode : Icons.dark_mode),
                onPressed: ThemeService.toggleTheme,
                tooltip: isDark ? 'Switch to light mode' : 'Switch to dark mode',
              );
            },
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loading ? null : _load,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(_error!, textAlign: TextAlign.center),
                        const SizedBox(height: 16),
                        FilledButton(
                          onPressed: _load,
                          child: const Text('Retry'),
                        ),
                      ],
                    ),
                  ),
                )
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // Search and sort fixed at top so always visible
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                      child: _buildSearchAndSort(),
                    ),
                    const SizedBox(height: 8),
                    Expanded(
                      child: RefreshIndicator(
                        onRefresh: _load,
                        child: ListView(
                          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                          children: [
                            _buildFirewallCard(),
                            const SizedBox(height: 12),
                            _buildBatteryOptimizationCard(),
                            const SizedBox(height: 12),
                            _buildDomainIpBlockCard(context),
                            const SizedBox(height: 12),
                            _buildActiveConnectionsCard(context),
                            const SizedBox(height: 24),
                            Text(
                              'Block apps from WiFi & mobile data',
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            const SizedBox(height: 8),
                            ..._filteredAndSortedApps.map((app) => _buildAppTile(app)),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
    );
  }

  Widget _buildSearchAndSort() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _searchController,
          decoration: InputDecoration(
            hintText: 'Search apps...',
            prefixIcon: const Icon(Icons.search),
            suffixIcon: _searchQuery.isNotEmpty
                ? IconButton(
                    icon: const Icon(Icons.clear),
                    onPressed: () {
                      _searchController.clear();
                      setState(() => _searchQuery = '');
                    },
                  )
                : null,
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
            ),
            filled: true,
          ),
          onChanged: (value) => setState(() => _searchQuery = value),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            Text(
              'Sort by:',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(width: 12),
            DropdownButton<AppSortOption>(
              value: _sortOption,
              underline: const SizedBox(),
              items: const [
                DropdownMenuItem(
                  value: AppSortOption.mostUsed,
                  child: Text('Most used'),
                ),
                DropdownMenuItem(
                  value: AppSortOption.aToZ,
                  child: Text('A–Z'),
                ),
                DropdownMenuItem(
                  value: AppSortOption.zToA,
                  child: Text('Z–A'),
                ),
              ],
              onChanged: (value) {
                if (value != null) setState(() => _sortOption = value);
              },
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildFirewallCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  _firewallOn ? Icons.security : Icons.security_outlined,
                  size: 40,
                  color: _firewallOn
                      ? Theme.of(context).colorScheme.primary
                      : Theme.of(context).colorScheme.outline,
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _firewallOn ? 'Firewall is ON' : 'Firewall is OFF',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      Text(
                        _firewallOn
                            ? 'Blocked apps cannot use network'
                            : 'Turn on to block selected apps',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                            ),
                      ),
                    ],
                  ),
                ),
                Switch(
                  value: _firewallOn,
                  onChanged: _vpnPermissionGranted
                      ? _toggleFirewall
                      : (on) async {
                          if (on) await _requestVpnPermission();
                        },
                ),
              ],
            ),
            if (!_vpnPermissionGranted) ...[
              const SizedBox(height: 12),
              FilledButton.tonal(
                onPressed: _requestVpnPermission,
                child: const Text('Grant VPN permission'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildBatteryOptimizationCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(
              _batteryOptIgnored ? Icons.battery_saver : Icons.battery_alert,
              size: 32,
              color: _batteryOptIgnored
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.error,
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Battery optimization',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  Text(
                    'Exempt MobiWall from battery optimization to keep the firewall running',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                ],
              ),
            ),
            if (!_batteryOptIgnored)
              FilledButton.tonal(
                onPressed: _requestBatteryOptimizationExemption,
                child: const Text('Request'),
              )
            else
              Icon(
                Icons.check_circle,
                color: Theme.of(context).colorScheme.primary,
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildDomainIpBlockCard(BuildContext context) {
    return Card(
      child: InkWell(
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const DomainIpBlockScreen()),
        ),
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(
                Icons.link_off,
                size: 32,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Block domains & IPs',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    Text(
                      'Block incoming and outgoing requests by domain or IP',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildActiveConnectionsCard(BuildContext context) {
    return Card(
      child: InkWell(
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const ActiveConnectionsScreen()),
        ),
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(
                Icons.analytics,
                size: 32,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Active connections',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    Text(
                      'Monitor active IPs & domains; sort by data or time; block on WiFi or mobile',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAppIcon(AppInfo app) {
    if (app.iconBase64 != null) {
      try {
        final bytes = base64Decode(app.iconBase64!);
        return CircleAvatar(
          radius: 24,
          backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
          backgroundImage: MemoryImage(bytes),
        );
      } catch (_) {}
    }
    return CircleAvatar(
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Text(
        app.name.isNotEmpty ? app.name[0].toUpperCase() : '?',
        style: Theme.of(context).textTheme.titleMedium,
      ),
    );
  }

  Widget _buildAppTile(AppInfo app) {
    final blockWifi = _blockedUidsWifi.contains(app.uidString);
    final blockMobile = _blockedUidsMobile.contains(app.uidString);
    final anyBlocked = blockWifi || blockMobile;
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: _buildAppIcon(app),
              title: Text(app.name),
              subtitle: Text(
                anyBlocked
                    ? (blockWifi && blockMobile
                        ? 'Blocked on WiFi & mobile data'
                        : blockWifi
                            ? 'Blocked on WiFi'
                            : 'Blocked on mobile data')
                    : 'Allowed',
                style: TextStyle(
                  color: anyBlocked
                      ? Theme.of(context).colorScheme.error
                      : Theme.of(context).colorScheme.onSurfaceVariant,
                  fontSize: 12,
                ),
              ),
            ),
            const SizedBox(height: 4),
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
                        onChanged: (v) => _toggleBlockWifi(app, v),
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
                        onChanged: (v) => _toggleBlockMobile(app, v),
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
  }
}
