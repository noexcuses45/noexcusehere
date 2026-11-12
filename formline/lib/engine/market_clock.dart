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

  /// Minutes from now until [hour]:[minute] Sydney time today (negative once passed).
  static int minutesUntil(int hour, int minute, [DateTime? now]) {
    final s = sydney(now);
    return hour * 60 + minute - (s.hour * 60 + s.minute);
  }

  static String _clock(int minutes) {
    final m = ((minutes % 1440) + 1440) % 1440;
    final h = m ~/ 60;
    return '${h % 12 == 0 ? 12 : h % 12}:${(m % 60).toString().padLeft(2, '0')}${h < 12 ? 'am' : 'pm'}';
  }

  /// A Sydney clock time such as "3:50pm". When the phone is on another time
  /// zone its own time is added: "3:50pm Sydney (2:50pm your time)".
  static String timeLabel(int hour, int minute, [DateTime? now]) {
    final n = now ?? DateTime.now();
    final diff = n.timeZoneOffset - sydneyOffset(n.toUtc());
    final syd = _clock(hour * 60 + minute);
    if (diff == Duration.zero) return syd;
    return '$syd Sydney (${_clock(hour * 60 + minute + diff.inMinutes)} your time)';
  }

  /// The date of the latest ASX session that has started (Sydney calendar
  /// date at midnight UTC, the way price bars are dated).
  static DateTime lastSessionDate([DateTime? now]) {
    final s = sydney(now);
    var d = DateTime.utc(s.year, s.month, s.day);
    if (s.hour < 10) d = d.subtract(const Duration(days: 1));
    while (d.weekday > 5) {
      d = d.subtract(const Duration(days: 1));
    }
    return d;
  }
}
