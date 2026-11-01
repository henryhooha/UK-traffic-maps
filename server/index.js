// HTTP server: static front-end + JSON API. No framework, just node:http.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { config } from './config.js';
import { createProviders } from './providers/index.js';
import { createPlanner } from './lib/planner.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const STATIC = { '/': path.join(ROOT, 'public'), '/shared/': path.join(ROOT, 'shared') };
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json', '.png': 'image/png' };

export function createApp({ cfg = config, fetchImpl = fetch } = {}) {
  const providers = createProviders(cfg, { fetchImpl });
  const planner = createPlanner({ config: cfg, providers, fetchImpl });
  const geocodeCache = new Map();
  // Nominatim's usage policy is max 1 request/second — queue requests.
  let geoChain = Promise.resolve();
  const throttled = (fn) => {
    const run = geoChain.then(fn);
    geoChain = run.catch(() => {}).then(() => new Promise((r) => setTimeout(r, 1100)));
    return run;
  };

  const json = (res, code, body) => {
    res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(body));
  };
  const readBody = (req) => new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 2e6) { reject(new Error('Body too large')); req.destroy(); } });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch { reject(new Error('Invalid JSON')); } });
  });
  const isPt = (p) => Array.isArray(p) && p.length === 2 && p.every(Number.isFinite) && Math.abs(p[1]) <= 90 && Math.abs(p[0]) <= 180;

  const api = {
    'GET /api/status': async () => ({ demo: cfg.demo, providers: providers.status() }),

    'GET /api/geocode': async (req, url) => {
      const q = (url.searchParams.get('q') || '').trim();
      if (q.length < 2) return [];
      if (geocodeCache.has(q)) return geocodeCache.get(q);
      const u = `${cfg.nominatimUrl}/search?format=jsonv2&limit=6&countrycodes=gb&q=${encodeURIComponent(q)}${cfg.contactEmail ? `&email=${encodeURIComponent(cfg.contactEmail)}` : ''}`;
      if (geocodeCache.has(q)) return geocodeCache.get(q);
      const r = await throttled(() => fetchImpl(u, { headers: { 'user-agent': 'uk-traffic-maps/0.1', 'accept-language': 'en-GB' } }));
      if (!r.ok) throw Object.assign(new Error(`Geocoder HTTP ${r.status}`), { status: 502 });
      const out = (await r.json()).map((x) => ({ name: x.display_name, location: [Number(x.lon), Number(x.lat)] }));
      geocodeCache.set(q, out);
      return out;
    },

    'GET /api/events': async (req, url) => {
      const bbox = (url.searchParams.get('bbox') || '').split(',').map(Number);
      let evs = providers.events().filter((e) => !(e.type === 'signal' && !e.speedLimit && !e.lanesClosed?.length && !e.signal?.lanes?.some((l) => l.aspect !== 'blank')));
      if (bbox.length === 4 && bbox.every(Number.isFinite)) {
        const [a, b, c, d] = bbox;
        evs = evs.filter(({ location: [lo, la] }) => lo >= a && lo <= c && la >= b && la <= d);
      }
      return evs.slice(0, 3000);
    },

    'POST /api/route': async (req) => {
      const { from, to } = await readBody(req);
      if (!isPt(from) || !isPt(to)) throw Object.assign(new Error('from/to must be [lon,lat]'), { status: 400 });
      return { routes: await planner.plan(from, to) };
    },

    'POST /api/analyse': async (req) => {
      const { routeId } = await readBody(req);
      const r = await planner.reanalyse(routeId);
      if (!r) throw Object.assign(new Error('Route expired — please re-plan'), { status: 410 });
      return r;
    },

    'POST /api/divert': async (req) => {
      const { routeId, hazardId, position, to } = await readBody(req);
      return planner.divert(routeId, hazardId, isPt(position) ? position : null, isPt(to) ? to : null);
    },
  };

  function serveStatic(req, res, pathname) {
    const prefix = pathname.startsWith('/shared/') ? '/shared/' : '/';
    const base = STATIC[prefix];
    const rel = pathname.slice(prefix.length) || 'index.html';
    const file = path.normalize(path.join(base, rel));
    if (!file.startsWith(base)) { res.writeHead(403).end(); return; }
    fs.stat(file, (err, st) => {
      if (err || !st.isFile()) { res.writeHead(404, { 'content-type': 'text/plain' }).end('Not found'); return; }
      res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', 'cache-control': 'no-cache' });
      fs.createReadStream(file).pipe(res);
    });
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const handler = api[`${req.method} ${url.pathname}`];
    if (handler) {
      try { json(res, 200, await handler(req, url)); } catch (e) { json(res, e.status || 500, { error: e.message }); }
      return;
    }
    if (url.pathname.startsWith('/api/')) return json(res, 404, { error: 'Not found' });
    serveStatic(req, res, url.pathname);
  });

  return { server, providers, planner };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { server, providers } = createApp();
  providers.start().then(() => {
    server.listen(config.port, () => {
      console.log(`UK Road Ahead running on http://localhost:${config.port}`);
      for (const p of providers.status()) console.log(`  • ${p.label}: ${p.lastError ? 'ERROR ' + p.lastError : `${p.count} events`}`);
      if (config.demo) console.log('  ⚠ Demo smart-motorway simulator is ON (no NTIS feed configured). Simulated items are labelled DEMO.');
    });
  });
}
