/// Represents an installed app for firewall rules.
class AppInfo {
  final String packageName;
  final String name;
  final int uid;
  /// Base64-encoded PNG of app icon (optional).
  final String? iconBase64;

  const AppInfo({
    required this.packageName,
    required this.name,
    required this.uid,
    this.iconBase64,
  });

  factory AppInfo.fromMap(Map<String, dynamic> m) {
    final icon = m['icon'] as String?;
    return AppInfo(
      packageName: m['packageName'] as String? ?? '',
      name: m['name'] as String? ?? '',
      uid: (m['uid'] is int) ? m['uid'] as int : int.tryParse(m['uid'].toString()) ?? 0,
      iconBase64: (icon != null && icon.isNotEmpty) ? icon : null,
    );
  }

  String get uidString => uid.toString();
}
