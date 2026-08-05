import 'package:flutter_test/flutter_test.dart';

import 'package:mobiwall/main.dart';

void main() {
  testWidgets('App loads and shows firewall UI', (WidgetTester tester) async {
    await tester.pumpWidget(const MobiWallApp());
    await tester.pumpAndSettle();
    expect(find.text('MobiWall Firewall'), findsOneWidget);
  });
}
