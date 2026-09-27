import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Service to manage theme mode persistence across app restarts.
class ThemeService {
  static const _key = 'mobiwall_theme_mode';

  static final ValueNotifier<ThemeMode> themeNotifier =
      ValueNotifier(ThemeMode.system);

  /// Loads the saved theme mode from SharedPreferences and applies it.
  static Future<void> loadTheme() async {
    final mode = await getThemeMode();
    themeNotifier.value = mode;
  }

  /// Returns the saved theme mode, defaulting to ThemeMode.system.
  static Future<ThemeMode> getThemeMode() async {
    final prefs = await SharedPreferences.getInstance();
    final value = prefs.getString(_key);
    return _fromString(value);
  }

  /// Saves the given theme mode to SharedPreferences and updates the notifier.
  static Future<void> setThemeMode(ThemeMode mode) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, mode.name);
    themeNotifier.value = mode;
  }

  /// Toggles between light and dark theme, saving the result.
  static Future<void> toggleTheme() async {
    final current = themeNotifier.value;
    final next = current == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark;
    await setThemeMode(next);
  }

  static ThemeMode _fromString(String? value) {
    switch (value) {
      case 'light':
        return ThemeMode.light;
      case 'dark':
        return ThemeMode.dark;
      default:
        return ThemeMode.system;
    }
  }
}
