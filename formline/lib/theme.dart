import 'package:flutter/material.dart';

class EdgeColors {
  static const navy = Color(0xFF0E1B2A);
  static const navyCard = Color(0xFF16263A);
  static const lime = Color(0xFFB8F23D);
  static const limeDark = Color(0xFF7FB814);
  static const coral = Color(0xFFFF6B4A);
  static const sky = Color(0xFF5CC8FF);
  static const text = Color(0xFFF2F5F7);
  static const muted = Color(0xFF9AA8B8);
  static const win = Color(0xFF3DDC84);
  static const loss = Color(0xFFFF5C5C);
}

ThemeData edgeTheme() {
  final base = ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: ColorScheme.fromSeed(
      seedColor: EdgeColors.lime,
      brightness: Brightness.dark,
      primary: EdgeColors.lime,
      secondary: EdgeColors.sky,
      surface: EdgeColors.navyCard,
    ),
    scaffoldBackgroundColor: EdgeColors.navy,
  );
  return base.copyWith(
    appBarTheme: const AppBarTheme(
      backgroundColor: EdgeColors.navy,
      foregroundColor: EdgeColors.text,
      elevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardThemeData(
      color: EdgeColors.navyCard,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      margin: EdgeInsets.zero,
    ),
    chipTheme: ChipThemeData(
      backgroundColor: EdgeColors.navyCard,
      selectedColor: EdgeColors.lime,
      labelStyle: const TextStyle(color: EdgeColors.text, fontWeight: FontWeight.w600),
      secondaryLabelStyle: const TextStyle(color: EdgeColors.navy, fontWeight: FontWeight.w700),
      side: BorderSide.none,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: EdgeColors.lime,
        foregroundColor: EdgeColors.navy,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: EdgeColors.navyCard,
      indicatorColor: EdgeColors.lime.withValues(alpha: 0.25),
      labelTextStyle: WidgetStateProperty.all(const TextStyle(color: EdgeColors.text, fontSize: 12)),
      iconTheme: WidgetStateProperty.all(const IconThemeData(color: EdgeColors.text)),
    ),
    dividerColor: Colors.white12,
    textTheme: base.textTheme.apply(bodyColor: EdgeColors.text, displayColor: EdgeColors.text),
  );
}
