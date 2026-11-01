// Give OSRM's anonymous motorway diverge points their junction numbers
// ("J13") using OpenStreetMap highway=motorway_junction nodes via Overpass.
// Optional: if Overpass is unreachable the exits are kept unnamed.

import { haversine } from '../../shared/geo.js';

const cache = new Map(); // "lon,lat" (rounded) -> {ref,name}|null

export async function lookupJunctionNames(exits, config, fetchImpl = fetch) {
  if (!exits?.length || !config.overpass) return exits || [];
  const key = (p) => `${p[0].toFixed(3)},${p[1].toFixed(3)}`;
  const todo = exits.filter((e) => !cache.has(key(e.location)));
  if (todo.length) {
    const parts = todo.slice(0, 150).map((e) => `node(around:900,${e.location[1]},${e.location[0]})[highway=motorway_junction];`);
    const q = `[out:json][timeout:15];(${parts.join('')});out;`;
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 12000);
    try {
      const res = await fetchImpl(config.overpassUrl, {
        method: 'POST',
        body: 'data=' + encodeURIComponent(q),
        headers: { 'content-type': 'application/x-www-form-urlencoded', 'user-agent': 'uk-traffic-maps' },
        signal: ctrl.signal,
      });
      if (res.ok) {
        const nodes = (await res.json()).elements || [];
        for (const e of todo) {
          let best = null;
          for (const n of nodes) {
            const d = haversine(e.location, [n.lon, n.lat]);
            if (d < 900 && (!best || d < best.d)) best = { d, ref: n.tags?.ref || null, name: n.tags?.name || null };
          }
          cache.set(key(e.location), best ? { ref: best.ref, name: best.name } : null);
        }
      }
    } catch { /* offline / rate-limited: leave unnamed */ } finally { clearTimeout(t); }
  }
  return exits.map((e) => {
    const hit = cache.get(key(e.location));
    return hit ? { ...e, ref: hit.ref ? `J${hit.ref.replace(/^J/i, '')}` : null, name: hit.name } : e;
  });
}
