import 'package:flutter/material.dart';

import 'screens/home_screen.dart';
import 'services/theme_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  ThemeService.loadTheme();
  runApp(const MobiWallApp());
}

class MobiWallApp extends StatelessWidget {
  const MobiWallApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: ThemeService.themeNotifier,
      builder: (context, themeMode, _) {
        return MaterialApp(
          title: 'MobiWall',
          theme: ThemeData(
            colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepOrange),
            useMaterial3: true,
          ),
          darkTheme: ThemeData(
            colorScheme: ColorScheme.fromSeed(
              seedColor: Colors.deepOrange,
              brightness: Brightness.dark,
            ),
            useMaterial3: true,
          ),
          themeMode: themeMode,
          home: const HomeScreen(),
        );
      },
    );
  }
}
