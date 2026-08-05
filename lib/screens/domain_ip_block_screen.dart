import 'dart:io';

import 'package:flutter/material.dart';

import '../services/firewall_service.dart';

/// Screen to manage blocked domains and IPs (incoming and outgoing).
class DomainIpBlockScreen extends StatefulWidget {
  const DomainIpBlockScreen({super.key});

  @override
  State<DomainIpBlockScreen> createState() => _DomainIpBlockScreenState();
}

class _DomainIpBlockScreenState extends State<DomainIpBlockScreen> {
  Set<String> _blockedDomains = {};
  Set<String> _blockedIps = {};
  bool _loading = true;
  final _domainController = TextEditingController();
  final _ipController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _domainController.dispose();
    _ipController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    if (!Platform.isAndroid) {
      setState(() {
        _loading = false;
        _blockedDomains = {};
        _blockedIps = {};
      });
      return;
    }
    setState(() => _loading = true);
    try {
      final domains = await FirewallService.getBlockedDomains();
      final ips = await FirewallService.getBlockedIps();
      setState(() {
        _blockedDomains = domains.toSet();
        _blockedIps = ips.toSet();
        _loading = false;
      });
    } catch (_) {
      setState(() => _loading = false);
    }
  }

  Future<void> _addDomain() async {
    final domain = _domainController.text.trim().toLowerCase();
    if (domain.isEmpty) return;
    _domainController.clear();
    final next = Set<String>.from(_blockedDomains)..add(domain);
    setState(() => _blockedDomains = next);
    await FirewallService.setBlockedDomains(next.toList());
  }

  Future<void> _removeDomain(String domain) async {
    final next = Set<String>.from(_blockedDomains)..remove(domain);
    setState(() => _blockedDomains = next);
    await FirewallService.setBlockedDomains(next.toList());
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

  Future<void> _addIp() async {
    final ip = _ipController.text.trim();
    if (ip.isEmpty) return;
    if (!_looksLikeIp(ip)) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Enter a valid IPv4 address (e.g. 192.168.1.1)')),
        );
      }
      return;
    }
    _ipController.clear();
    final next = Set<String>.from(_blockedIps)..add(ip);
    setState(() => _blockedIps = next);
    await FirewallService.setBlockedIps(next.toList());
  }

  Future<void> _removeIp(String ip) async {
    final next = Set<String>.from(_blockedIps)..remove(ip);
    setState(() => _blockedIps = next);
    await FirewallService.setBlockedIps(next.toList());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Block domains & IPs'),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : !Platform.isAndroid
              ? const Center(child: Text('Only supported on Android.'))
              : SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        'Block incoming and outgoing requests to these domains and IPs when the firewall is on. '
                        'Blocked domains are filtered via DNS; blocked IPs drop matching packets. When any rule is active, only DNS and UDP are forwarded.',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                            ),
                      ),
                      const SizedBox(height: 24),
                      _buildSection(
                        context,
                        title: 'Blocked domains',
                        hint: 'e.g. example.com',
                        controller: _domainController,
                        onAdd: _addDomain,
                        items: _blockedDomains.toList()..sort(),
                        onRemove: _removeDomain,
                      ),
                      const SizedBox(height: 24),
                      _buildSection(
                        context,
                        title: 'Blocked IPs',
                        hint: 'e.g. 192.168.1.1',
                        controller: _ipController,
                        onAdd: _addIp,
                        items: _blockedIps.toList()..sort(),
                        onRemove: _removeIp,
                      ),
                    ],
                  ),
                ),
    );
  }

  Widget _buildSection(
    BuildContext context, {
    required String title,
    required String hint,
    required TextEditingController controller,
    required VoidCallback onAdd,
    required List<String> items,
    required void Function(String) onRemove,
  }) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    decoration: InputDecoration(
                      hintText: hint,
                      border: const OutlineInputBorder(),
                      isDense: true,
                    ),
                    onSubmitted: (_) => onAdd(),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: onAdd,
                  child: const Text('Add'),
                ),
              ],
            ),
            if (items.isNotEmpty) ...[
              const SizedBox(height: 12),
              ...items.map((item) => ListTile(
                    title: Text(item),
                    trailing: IconButton(
                      icon: const Icon(Icons.remove_circle_outline),
                      onPressed: () => onRemove(item),
                    ),
                  )),
            ],
          ],
        ),
      ),
    );
  }
}
