/// Sydney time and ASX session helpers (no timezone database needed).
class MarketClock {
  /// First Sunday of [month] in [year], as a UTC date.
  static DateTime _firstSunday(int year, int month) {
    final d = DateTime.utc(year, month, 1);
    return d.add(Duration(days: (7 - d.weekday) % 7));
  }

  /// Sydney's UTC offset: +11 during daylight saving (first Sunday in October
  /// to first Sunday in April), otherwise +10.
  static Duration sydneyOffset(DateTime utc) {
    final y = utc.year;
    // DST ends 3am AEDT (16:00 UTC the day before) and starts 2am AEST (16:00 UTC the day before).
    final end = _firstSunday(y, 4).subtract(const Duration(hours: 8));
    final start = _firstSunday(y, 10).subtract(const Duration(hours: 8));
    final dst = utc.isBefore(end) || !utc.isBefore(start);
    return Duration(hours: dst ? 11 : 10);
  }

  /// Wall-clock time in Sydney, returned as a UTC-flagged DateTime.
  static DateTime sydney([DateTime? now]) {
    final utc = (now ?? DateTime.now()).toUtc();
    return utc.add(sydneyOffset(utc));
  }

  /// True between the 10:00 open and the end of the 4:10pm closing auction.
  static bool asxOpen([DateTime? now]) {
    final s = sydney(now);
    if (s.weekday > 5) return false;
    final mins = s.hour * 60 + s.minute;
    return mins >= 10 * 60 && mins < 16 * 60 + 12;
  }

  /// Whether a bar dated [barDate] is still forming (today's session not finished).
  static bool barIsLive(DateTime barDate, [DateTime? now]) {
    final s = sydney(now);
    final sameDay = barDate.year == s.year && barDate.month == s.month && barDate.day == s.day;
    return sameDay && asxOpen(now);
  }
}
