import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../theme.dart';

class LeaderboardScreen extends StatefulWidget {
  const LeaderboardScreen({super.key});

  @override
  State<LeaderboardScreen> createState() => _LeaderboardScreenState();
}

class _LeaderboardScreenState extends State<LeaderboardScreen> {
  List<Map<String, dynamic>> _rows = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final rows = await Supabase.instance.client
          .from('nx_weekly_leaderboard')
          .select()
          .limit(100);
      if (mounted) {
        setState(() {
          _rows = List<Map<String, dynamic>>.from(rows);
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Color _avatarColor(int i) {
    const colors = [
      NxColors.coral,
      Color(0xFF534AB7),
      NxColors.teal,
      Color(0xFF993556),
      Color(0xFF185FA5),
    ];
    return colors[i % colors.length];
  }

  @override
  Widget build(BuildContext context) {
    final myId = Supabase.instance.client.auth.currentUser!.id;
    final fmt = NumberFormat.decimalPattern();
    return Scaffold(
      appBar: AppBar(
        title: const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text("This week's steps",
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
            Text('Resets Monday',
                style: TextStyle(fontSize: 12, color: Colors.grey)),
          ],
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: _rows.length,
                itemBuilder: (context, i) {
                  final row = _rows[i];
                  final isMe = row['user_id'] == myId;
                  final name = row['display_name'] as String? ?? 'Member';
                  final initials = name.trim().isEmpty
                      ? '?'
                      : name
                          .trim()
                          .split(RegExp(r'\s+'))
                          .take(2)
                          .map((w) => w[0].toUpperCase())
                          .join();
                  return Card(
                    margin: const EdgeInsets.only(bottom: 8),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                      side: isMe
                          ? const BorderSide(color: NxColors.teal, width: 1.5)
                          : BorderSide.none,
                    ),
                    child: ListTile(
                      leading: SizedBox(
                        width: 62,
                        child: Row(
                          children: [
                            SizedBox(
                              width: 22,
                              child: Text('${i + 1}',
                                  textAlign: TextAlign.center,
                                  style: TextStyle(
                                    fontWeight: FontWeight.w700,
                                    color: i == 0
                                        ? const Color(0xFFBA7517)
                                        : Colors.grey.shade600,
                                  )),
                            ),
                            const SizedBox(width: 6),
                            CircleAvatar(
                              radius: 16,
                              backgroundColor: _avatarColor(i),
                              child: Text(initials,
                                  style: const TextStyle(
                                      fontSize: 11, color: Colors.white)),
                            ),
                          ],
                        ),
                      ),
                      title: Text(isMe ? 'You' : name,
                          style: TextStyle(
                              fontWeight:
                                  isMe ? FontWeight.w700 : FontWeight.w400)),
                      trailing: Text(fmt.format(row['week_steps'] ?? 0),
                          style: const TextStyle(
                              fontSize: 15, fontWeight: FontWeight.w700)),
                    ),
                  );
                },
              ),
            ),
    );
  }
}
