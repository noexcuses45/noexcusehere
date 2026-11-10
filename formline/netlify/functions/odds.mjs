// Shared bookmaker-odds relay for Formline.
// The Odds API key lives only here, as the ODDS_API_KEY environment variable
// of the Netlify site. It is never sent to the app or the browser.
// Responses are cached at the edge for 20 minutes, so every phone using the
// app shares one upstream request per league and credits last longer.
export default async (req, context) => {
  const headers = {
    'content-type': 'application/json',
    'access-control-allow-origin': '*',
    'access-control-expose-headers': 'x-requests-remaining',
  };
  const key = Netlify.env.get('ODDS_API_KEY');
  if (!key) {
    return new Response(JSON.stringify({ error: 'not_configured' }), { status: 503, headers });
  }
  const sport = context.params.sport || '';
  if (!/^[a-z0-9_]{3,60}$/.test(sport)) {
    return new Response(JSON.stringify({ error: 'bad_sport' }), { status: 400, headers });
  }
  const url =
    `https://api.the-odds-api.com/v4/sports/${sport}/odds` +
    `?regions=au&markets=h2h,spreads,totals&oddsFormat=decimal&apiKey=${encodeURIComponent(key)}`;
  let upstream;
  try {
    upstream = await fetch(url);
  } catch (e) {
    return new Response(JSON.stringify({ error: 'upstream_unreachable' }), { status: 502, headers });
  }
  if (!upstream.ok) {
    // Never forward upstream error bodies: keep anything key-related private.
    const status = upstream.status === 429 ? 429 : (upstream.status === 404 ? 404 : 502);
    return new Response(JSON.stringify({ error: 'upstream', status: upstream.status }), { status, headers });
  }
  const body = await upstream.text();
  return new Response(body, {
    status: 200,
    headers: {
      ...headers,
      'x-requests-remaining': upstream.headers.get('x-requests-remaining') || '',
      'cache-control': 'public, max-age=300',
      'netlify-cdn-cache-control': 'public, durable, max-age=1200',
    },
  });
};

export const config = { path: '/odds-api/:sport' };
