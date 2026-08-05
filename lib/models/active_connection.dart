/// A currently active connection (IP or domain) with traffic stats.
class ActiveConnection {
  final String host;
  final String display;
  final int bytesSent;
  final int bytesReceived;
  final int firstSeenMs;

  const ActiveConnection({
    required this.host,
    required this.display,
    required this.bytesSent,
    required this.bytesReceived,
    required this.firstSeenMs,
  });

  factory ActiveConnection.fromMap(Map<Object?, Object?> map) {
    final getStr = (Object? k) => (map[k]?.toString()) ?? '';
    final getInt = (Object? k) {
      final v = map[k];
      if (v is int) return v;
      if (v is num) return v.toInt();
      // Platform may send Long as int or as double
      final s = v?.toString() ?? '';
      return int.tryParse(s) ?? 0;
    };
    return ActiveConnection(
      host: getStr('host'),
      display: getStr('display').isNotEmpty ? getStr('display') : getStr('host'),
      bytesSent: getInt('bytesSent'),
      bytesReceived: getInt('bytesReceived'),
      firstSeenMs: getInt('firstSeenMs'),
    );
  }

  int get totalBytes => bytesSent + bytesReceived;

  Duration get runningTime =>
      Duration(milliseconds: DateTime.now().millisecondsSinceEpoch - firstSeenMs);
}
