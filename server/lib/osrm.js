// Routing via an OSRM server (public demo by default; run your own for production).
// We also mine OSRM's per-step intersections for motorway diverge points —
// these are the exits you can actually leave by.

export const isMotorwayRef = (ref) => /(^|;)\s*(M\d+|A\d+\(M\))/i.test(ref || '');

export async function osrmRoute(baseUrl, waypoints, { alternatives = 2, fetchImpl = fetch } = {}) {
  const coords = waypoints.map(([lon, lat]) => `${lon.toFixed(6)},${lat.toFixed(6)}`).join(';');
  const alt = waypoints.length === 2 && alternatives ? `&alternatives=${alternatives}` : '';
  const url = `${baseUrl.replace(/\/$/, '')}/route/v1/driving/${coords}?overview=full&geometries=geojson&steps=true${alt}`;
  const res = await fetchImpl(url, { headers: { 'user-agent': 'uk-traffic-maps' } });
  if (!res.ok) throw new Error(`Routing failed (HTTP ${res.status})`);
  const data = await res.json();
  if (data.code !== 'Ok') throw new Error(`Routing failed: ${data.message || data.code}`);
  return data.routes.map(normaliseRoute);
}

export function normaliseRoute(r) {
  const steps = [];
  const exits = [];
  let along = 0;
  for (const leg of r.legs) {
    for (const s of leg.steps) {
      const from = along;
      along += s.distance;
      steps.push({ from, to: along, ref: s.ref || null, name: s.name || null, maneuver: s.maneuver?.type, modifier: s.maneuver?.modifier });
      const motorway = isMotorwayRef(s.ref) || (s.intersections || []).some((i) => i.classes?.includes('motorway'));
      if (!motorway) continue;
      (s.intersections || []).forEach((i, idx) => {
        if (idx === 0) return; // the step's own manoeuvre point
        const options = (i.entry || []).filter(Boolean).length;
        if (options >= 2) exits.push({ ref: null, name: null, location: i.location, road: s.ref });
      });
    }
  }
  return {
    coords: r.geometry.coordinates,
    distance: r.distance,
    duration: r.duration,
    steps,
    exits,
    summary: summarise(steps),
  };
}

function summarise(steps) {
  const by = new Map();
  for (const s of steps) {
    const ref = (s.ref || '').split(';')[0].trim();
    if (!ref) continue;
    by.set(ref, (by.get(ref) || 0) + (s.to - s.from));
  }
  return [...by.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([r]) => r).join(', ');
}
