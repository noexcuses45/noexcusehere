import 'package:flutter/material.dart';

class NxColors {
  static const teal = Color(0xFF1D9E75);
  static const tealDark = Color(0xFF0F6E56);
  static const tealLight = Color(0xFF9FE1CB);
  static const coral = Color(0xFFD85A30);
  static const coralDark = Color(0xFF993C1D);
  static const coralLight = Color(0xFFF5C4B3);
  static const ink = Color(0xFF2C2C2A);
  static const surface = Color(0xFFF7F6F2);
}

ThemeData nxTheme() {
  final base = ThemeData(
    useMaterial3: true,
    colorScheme: ColorScheme.fromSeed(
      seedColor: NxColors.teal,
      primary: NxColors.teal,
      secondary: NxColors.coral,
    ),
    scaffoldBackgroundColor: NxColors.surface,
  );
  return base.copyWith(
    appBarTheme: const AppBarTheme(
      backgroundColor: NxColors.surface,
      foregroundColor: NxColors.ink,
      elevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardThemeData(
      color: Colors.white,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      margin: EdgeInsets.zero,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: NxColors.teal,
        foregroundColor: Colors.white,
        minimumSize: const Size.fromHeight(48),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: Colors.white,
      indicatorColor: NxColors.tealLight.withOpacity(0.5),
    ),
  );
}
