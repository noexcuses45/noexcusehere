import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../engine/market_clock.dart';
import '../engine/stocks.dart';
import 'cache.dart';

class CompanyInfo {
  CompanyInfo({required this.code, required this.name, this.sector = '', this.last, this.changePct, this.marketCap});
  final String code;
  final String name;
  final String sector;
  final double? last;
  final double? changePct;
  final double? marketCap;
}

/// ASX price history (the exchange's own chart service: daily closes and
/// volume, up to ten years) and company details.
class AsxApi {
  static final _client = http.Client();

  // The ASX services do not allow browser requests, so the web build uses
  // same-origin relays configured in web/_redirects.
  static Uri _chartUri(String code, int years) => kIsWeb
      ? Uri.base.resolve('/asx-chart/highcharts?asx_code=$code&years=$years')
      : Uri.parse('https://www.asx.com.au/asx/1/chart/highcharts?asx_code=$code&years=$years');
  static Uri _dataUri(String path) =>
      kIsWeb ? Uri.base.resolve('/asx-data/$path') : Uri.parse('https://asx.api.markitdigital.com/asx-research/1.0/$path');

  /// Daily history for [code]. Two-year histories are kept on the phone in
  /// compact form; longer ones stay in memory.
  static Future<Series?> history(String code, {int years = 2}) async {
    final key = 'asx:$code:$years';
    final ttl = MarketClock.asxOpen() ? const Duration(minutes: 15) : const Duration(hours: 3);
    final cached = await Cache.get(key, ttl);
    if (cached is Map && cached['d'] is List) return _fromCompact(code, cached);
    final res = await _client.get(_chartUri(code, years), headers: {'Accept': 'application/json'}).timeout(const Duration(seconds: 25));
    if (res.statusCode != 200) return null;
    final rows = json.decode(res.body);
    if (rows is! List || rows.isEmpty) return null;
    final days = <int>[], closes = <double>[];
    for (final r in rows) {
      if (r is! List || r.length < 2 || r[1] == null) continue;
      final c = (r[1] as num).toDouble();
      if (c <= 0) continue;
      days.add((r[0] as num).toInt() ~/ 86400000);
      closes.add(c);
    }
    if (days.length < 30) return null;
    // The service returns its full history whatever is asked for. Keep about
    // two years for the scan so the copy saved on the phone stays small.
    final keep = years <= 2 ? 520 : days.length;
    if (days.length > keep) {
      days.removeRange(0, days.length - keep);
      closes.removeRange(0, closes.length - keep);
    }
    final compact = {'d': days, 'c': closes};
    await Cache.put(key, compact, persist: years <= 2);
    return _fromCompact(code, compact);
  }

  static Series _fromCompact(String code, Map m) {
    final d = (m['d'] as List), c = (m['c'] as List);
    return Series(code, [
      for (var i = 0; i < d.length; i++) Bar(DateTime.fromMillisecondsSinceEpoch((d[i] as num).toInt() * 86400000, isUtc: true), (c[i] as num).toDouble(), 0),
    ]);
  }

  static Future<CompanyInfo?> company(String code) async {
    try {
      final res = await _client.get(_dataUri('companies/${code.toLowerCase()}/header'), headers: {'Accept': 'application/json'}).timeout(const Duration(seconds: 15));
      if (res.statusCode != 200) return null;
      final d = (json.decode(res.body) as Map)['data'] as Map?;
      if (d == null) return null;
      return CompanyInfo(
        code: '${d['symbol'] ?? code}'.toUpperCase(),
        name: _title('${d['displayName'] ?? code}'),
        sector: '${d['sector'] ?? ''}',
        last: (d['priceLast'] as num?)?.toDouble(),
        changePct: (d['priceChangePercent'] as num?)?.toDouble(),
        marketCap: (d['marketCap'] as num?)?.toDouble(),
      );
    } catch (_) {
      return null;
    }
  }

  /// Finds ASX codes by name or code.
  static Future<List<CompanyInfo>> search(String text) async {
    if (text.trim().length < 2) return [];
    try {
      final res = await _client
          .get(_dataUri('search/predictive?searchText=${Uri.encodeQueryComponent(text.trim())}'), headers: {'Accept': 'application/json'})
          .timeout(const Duration(seconds: 15));
      if (res.statusCode != 200) return [];
      final items = ((json.decode(res.body) as Map)['data'] as Map?)?['items'] as List? ?? [];
      return [
        for (final i in items)
          if (i is Map && i['symbol'] != null)
            CompanyInfo(code: '${i['symbol']}'.toUpperCase(), name: _title('${i['displayName'] ?? i['symbol']}'), sector: '${i['industryGroup'] ?? ''}'),
      ];
    } catch (_) {
      return [];
    }
  }

  static String _title(String s) => s
      .toLowerCase()
      .split(' ')
      .map((w) => w.isEmpty ? w : (w.length <= 3 && w == w.toLowerCase() && !['and', 'of', 'the'].contains(w) ? w.toUpperCase() : w[0].toUpperCase() + w.substring(1)))
      .join(' ')
      .replaceAll(RegExp(r'\.$'), '');
}
