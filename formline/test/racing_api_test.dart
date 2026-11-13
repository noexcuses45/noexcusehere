import 'package:flutter_test/flutter_test.dart';
import 'package:formline/services/racing_api.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

const body = '{"data":{"meetings":[{"meeting":"m1","name":"Flemington","date":"2026-11-13","country":"AUS","category":"T","category_name":"Thoroughbred",'
    '"track_condition":"Good","races":[{"id":"r1","race_number":1,"name":"Maiden","start_time":"2026-11-13T02:00:00Z","status":"Open","distance":1200}]}]}}';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});

  test('falls back to the website relay when the feed refuses the phone', () async {
    final hosts = <String>[];
    RacingApi.useClient(MockClient((req) async {
      hosts.add(req.url.host);
      return req.url.host == 'api.tab.co.nz' ? http.Response('blocked', 403) : http.Response(body, 200);
    }));
    final a = await RacingApi.meetings(DateTime(2026, 11, 13));
    expect(a.single.name, 'Flemington');
    expect(a.single.races.single.distance, 1200);
    expect(hosts, ['api.tab.co.nz', 'formline-app.netlify.app']);
    // Once the direct route has failed, the relay is tried first.
    await RacingApi.meetings(DateTime(2026, 11, 14));
    expect(hosts.last, 'formline-app.netlify.app');
    expect(hosts.length, 3);
  });

  test('says why when neither route works', () async {
    RacingApi.useClient(MockClient((req) async => http.Response('no', 403)));
    await expectLater(RacingApi.meetings(DateTime(2026, 11, 15)), throwsA(isA<RacingException>().having((e) => e.reason, 'reason', contains('403'))));
    RacingApi.useClient(MockClient((req) async => throw http.ClientException('offline')));
    await expectLater(RacingApi.meetings(DateTime(2026, 11, 16)), throwsA(isA<RacingException>().having((e) => e.reason, 'reason', contains('No connection'))));
  });
}
