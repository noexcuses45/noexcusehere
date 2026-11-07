import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../engine/predictor.dart';
import '../models/league.dart';
import 'espn_api.dart';
import 'racing_api.dart';

/// A saved selection: a sports multi or a racing pick. Graded automatically
/// once the event has finished, so the app keeps an honest track record.
class Pick {
  Pick({
    required this.id,
    required this.kind,
    required this.createdAt,
    required this.title,
    required this.detail,
    required this.prob,
    this.leagueId,
    this.eventId,
    this.eventDate,
    this.legs = const [],
    this.raceId,
    this.runnerNumber,
    this.runnerName,
    this.place = false,
    this.bookOdds,
    this.result,
  });

  final String id;
  final String kind; // sport | race
  final DateTime createdAt;
  final String title;
  final String detail;
  final double prob;
  final String? leagueId;
  final String? eventId;
  final DateTime? eventDate;
  final List<Leg> legs;
  final String? raceId;
  final int? runnerNumber;
  final String? runnerName;
  final bool place;
  double? bookOdds;
  String? result; // win | loss | void

  double get fairOdds => prob <= 0 ? 0 : 1 / prob;

  Map<String, dynamic> toJson() => {
        'id': id,
        'kind': kind,
        'createdAt': createdAt.toIso8601String(),
        'title': title,
        'detail': detail,
        'prob': prob,
        'leagueId': leagueId,
        'eventId': eventId,
        'eventDate': eventDate?.toIso8601String(),
        'legs': legs.map((l) => l.toJson()).toList(),
        'raceId': raceId,
        'runnerNumber': runnerNumber,
        'runnerName': runnerName,
        'place': place,
        'bookOdds': bookOdds,
        'result': result,
      };

  static Pick fromJson(Map<String, dynamic> j) => Pick(
        id: j['id'],
        kind: j['kind'],
        createdAt: DateTime.parse(j['createdAt']),
        title: j['title'] ?? '',
        detail: j['detail'] ?? '',
        prob: (j['prob'] as num?)?.toDouble() ?? 0,
        leagueId: j['leagueId'],
        eventId: j['eventId'],
        eventDate: j['eventDate'] == null ? null : DateTime.parse(j['eventDate']),
        legs: ((j['legs'] as List?) ?? []).map((l) => Leg.fromJson(Map<String, dynamic>.from(l))).toList(),
        raceId: j['raceId'],
        runnerNumber: j['runnerNumber'],
        runnerName: j['runnerName'],
        place: j['place'] == true,
        bookOdds: (j['bookOdds'] as num?)?.toDouble(),
        result: j['result'],
      );
}

class PicksStore extends ChangeNotifier {
  PicksStore._();
  static final PicksStore instance = PicksStore._();

  final List<Pick> picks = [];
  bool loaded = false;

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString('picks');
    picks.clear();
    if (raw != null) {
      try {
        for (final j in (json.decode(raw) as List)) {
          picks.add(Pick.fromJson(Map<String, dynamic>.from(j)));
        }
      } catch (_) {}
    }
    picks.sort((a, b) => b.createdAt.compareTo(a.createdAt));
    loaded = true;
    notifyListeners();
  }

  Future<void> _save() async {
    final p = await SharedPreferences.getInstance();
    await p.setString('picks', json.encode(picks.map((x) => x.toJson()).toList()));
    notifyListeners();
  }

  Future<void> add(Pick pick) async {
    picks.insert(0, pick);
    await _save();
  }

  Future<void> remove(String id) async {
    picks.removeWhere((p) => p.id == id);
    await _save();
  }

  Future<void> setBookOdds(String id, double? odds) async {
    final p = picks.firstWhere((x) => x.id == id);
    p.bookOdds = odds;
    await _save();
  }

  int get wins => picks.where((p) => p.result == 'win').length;
  int get losses => picks.where((p) => p.result == 'loss').length;

  /// Units returned per unit staked, at fair odds when no book odds entered.
  double get roi {
    var staked = 0.0, ret = 0.0;
    for (final p in picks) {
      if (p.result == 'win' || p.result == 'loss') {
        staked += 1;
        if (p.result == 'win') ret += p.bookOdds ?? p.fairOdds;
      }
    }
    return staked == 0 ? 0 : (ret - staked) / staked;
  }

  /// Grades every pending pick whose event should have finished.
  Future<int> gradePending() async {
    var graded = 0;
    for (final p in picks.where((p) => p.result == null).toList()) {
      try {
        if (p.kind == 'sport') {
          if (p.eventDate != null && DateTime.now().isBefore(p.eventDate!)) continue;
          final league = Leagues.byId(p.leagueId ?? '');
          if (league == null || p.eventId == null) continue;
          final s = await EspnApi.summary(league, p.eventId!);
          if (!s.completed || s.homeScore == null || s.awayScore == null) continue;
          p.result = _gradeSport(p, s) ? 'win' : 'loss';
          graded++;
        } else if (p.kind == 'race') {
          if (p.raceId == null) continue;
          final r = await RacingApi.race(p.raceId!);
          if (r.results.isEmpty) continue;
          final paid = p.place ? (r.positionsPaid > 0 ? r.positionsPaid : 3) : 1;
          final hit = r.results.any((x) => x.number == p.runnerNumber && x.position <= paid);
          final ran = r.runners.any((x) => x.number == p.runnerNumber && !x.scratched);
          p.result = !ran ? 'void' : (hit ? 'win' : 'loss');
          graded++;
        }
      } catch (_) {}
    }
    if (graded > 0) await _save();
    return graded;
  }

  bool _gradeSport(Pick p, EventSummary s) {
    final h = s.homeScore!, a = s.awayScore!;
    for (final leg in p.legs) {
      switch (leg.type) {
        case LegType.moneyline:
          if (!(leg.side == Side.home ? h > a : a > h)) return false;
          break;
        case LegType.handicap:
          if (!(leg.side == Side.home ? h - a + leg.line > 0 : a - h + leg.line > 0)) return false;
          break;
        case LegType.total:
          if (!(leg.over ? h + a > leg.line : h + a < leg.line)) return false;
          break;
        case LegType.teamTotal:
          final v = leg.side == Side.home ? h : a;
          if (!(leg.over ? v > leg.line : v < leg.line)) return false;
          break;
        case LegType.margin:
          final m = leg.side == Side.home ? h - a : a - h;
          if (!(m >= leg.lo && m <= leg.hi)) return false;
          break;
        case LegType.btts:
          final both = h > 0 && a > 0;
          if (leg.over != both) return false;
          break;
        case LegType.playerProp:
          final pl = leg.player!;
          final line = s.playerLines.where((l) => l.name == pl.name).toList();
          if (line.isEmpty) return false;
          final l = line.first;
          final v = switch (pl.stat) {
            'PTS' => l.pts,
            'REB' => l.reb,
            'AST' => l.ast,
            '3PM' => l.threes,
            _ => l.pra,
          };
          if (!(leg.over ? v > leg.line : v < leg.line)) return false;
          break;
      }
    }
    return true;
  }
}
