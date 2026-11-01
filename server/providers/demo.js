// Demo / simulator provider.
//
// Real smart-motorway signal data (NTIS) needs a National Highways subscription,
// so out of the box the app can generate *plausible* gantry settings, message
// signs and incidents on the motorways your route actually uses. Everything it
// produces is tagged source:'demo' and shown as DEMO in the UI.
//
// Scenarios are anchored to a fixed lat/lon + carriageway bearing, so the same
// incident shows up on every route that uses that carriageway (which is what
// makes "divert around it" meaningful).

import { RouteLine, bearingDiff } from '../../shared/geo.js';
import { isMotorwayRef } from '../lib/osrm.js';
import { signalTitle } from '../lib/datex2.js';

const GANTRY_SPACING = 1100; // metres; real smart motorway gantries are ~0.6–1.5 km apart
const TTL = 3 * 3600 * 1000;

function hash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) h = Math.imul(h ^ str.charCodeAt(i), 16777619);
  return h >>> 0;
}
function rng(seed) {
  let s = seed || 1;
  return () => ((s = Math.imul(s ^ (s >>> 15), 2246822507) ^ Math.imul(s ^ (s >>> 13), 3266489909)), (s >>> 0) / 4294967296);
}
const dirBucket = (b) => ['N', 'E', 'S', 'W'][Math.round(b / 90) % 4];

const SCENARIOS = [
  { kind: 'accident', weight: 3, lanes: (n) => [1, 2], title: 'Accident', vms: (mi) => ['ACCIDENT', '2 LANES CLOSED', `${mi} MILES`], delay: 18, severity: 'high' },
  { kind: 'breakdown', weight: 2, lanes: () => [1], title: 'Broken down vehicle', vms: (mi) => ['BROKEN DOWN', 'VEHICLE', `LANE 1 CLOSED`], delay: 6, severity: 'medium' },
  { kind: 'roadworks', weight: 2, lanes: () => [1], title: 'Roadworks', vms: () => ['ROADWORKS', 'LANE 1 CLOSED', '50 MPH'], delay: 5, severity: 'medium', speed: 50, zoneLen: 5000 },
  { kind: 'queue', weight: 2, lanes: () => [], title: 'Congestion — queue protection', vms: () => ['QUEUE', 'AHEAD'], delay: 9, severity: 'medium' },
  { kind: 'debris', weight: 1, lanes: (n) => [n], title: 'Debris in carriageway', vms: (mi) => ['DEBRIS', 'IN ROAD', 'OFFSIDE LANE CLOSED'], delay: 4, severity: 'medium' },
];

export function demoProvider({ now = () => Date.now() } = {}) {
  const store = new Map(); // id -> {event, expires}
  const scenarios = new Map(); // `${ref}:${dir}` -> scenario | null

  const put = (ev) => store.set(ev.id, { event: ev, expires: now() + TTL });

  function motorwayRuns(line, steps) {
    const runs = [];
    let cur = null;
    for (const s of steps) {
      const ref = (s.ref || '').split(';')[0].trim();
      if (isMotorwayRef(ref)) {
        if (cur && cur.ref === ref && s.from - cur.to < 50) cur.to = s.to;
        else { if (cur) runs.push(cur); cur = { ref, from: s.from, to: s.to }; }
      } else if (cur) { runs.push(cur); cur = null; }
    }
    if (cur) runs.push(cur);
    return runs.filter((r) => r.to - r.from > 9000).map((r) => ({ ...r, to: Math.min(r.to, line.length) }));
  }

  function scenarioFor(run, line) {
    const mid = (run.from + run.to) / 2;
    const dir = dirBucket(line.bearingAt(mid));
    const key = `${run.ref}:${dir}`;
    if (scenarios.has(key)) return scenarios.get(key);
    const hour = Math.floor(now() / (3 * 3600 * 1000));
    const r = rng(hash(key + hour));
    let sc = null;
    if (r() < 0.8) {
      const total = SCENARIOS.reduce((s, x) => s + x.weight, 0);
      let pick = r() * total;
      const def = SCENARIOS.find((x) => (pick -= x.weight) < 0) || SCENARIOS[0];
      // Leave at least ~6 km of gantries upstream so the warning builds up.
      const lo = run.from + Math.min(9000, (run.to - run.from) * 0.45);
      const hi = run.to - 1500;
      if (hi > lo) {
        const along = lo + r() * (hi - lo);
        const lanesTotal = /^M(1|6|25|4|5|62|60)$/.test(run.ref) ? 4 : 3;
        sc = {
          id: `demo:${key}:${hour}`,
          ref: run.ref,
          def,
          lanesTotal,
          lanes: def.lanes(lanesTotal),
          location: line.pointAt(along),
          bearing: line.bearingAt(along),
          startedMinsAgo: Math.round(5 + r() * 40),
        };
      }
    }
    scenarios.set(key, sc);
    if (sc) {
      put({
        id: sc.id,
        source: 'demo',
        type: sc.def.kind === 'queue' ? 'congestion' : sc.def.kind === 'roadworks' ? 'roadworks' : sc.def.kind === 'accident' ? 'accident' : 'incident',
        severity: sc.def.severity,
        road: sc.ref,
        direction: { N: 'northbound', E: 'eastbound', S: 'southbound', W: 'westbound' }[dirBucket(sc.bearing)],
        bearing: sc.bearing,
        location: sc.location,
        title: `${sc.def.title} · ${sc.ref}`,
        description: `${sc.def.title} on the ${sc.ref}.${sc.lanes.length ? ` Lane${sc.lanes.length > 1 ? 's' : ''} ${sc.lanes.join(' & ')} of ${sc.lanesTotal} closed.` : ''} Reported ${sc.startedMinsAgo} minutes ago. (Simulated data)`,
        lanesClosed: sc.lanes,
        lanesTotal: sc.lanesTotal,
        delayMinutes: sc.def.delay,
        updated: new Date(now()).toISOString(),
      });
    }
    return sc;
  }

  /** Gantry aspects given distance (m) upstream of the hazard (negative = past it). */
  function gantryFor(sc, d) {
    const n = sc.lanesTotal;
    const lanes = [];
    const set = (fn) => { for (let l = 1; l <= n; l++) lanes.push({ lane: l, ...fn(l) }); };
    const closed = new Set(sc.lanes);
    const k = sc.def.kind;
    if (d < -300) return null;
    if (d < 0) { set(() => ({ aspect: 'end' })); return lanes; }
    if (k === 'roadworks') {
      if (d > (sc.def.zoneLen || 5000) + 2500) return null;
      if (d > (sc.def.zoneLen || 5000)) { set(() => ({ aspect: 'speed', speed: 60 })); return lanes; }
      set((l) => (closed.has(l) && d < 1500 ? { aspect: 'closed' } : closed.has(l) && d < 2700 ? { aspect: 'merge_right' } : { aspect: 'speed', speed: 50 }));
      return lanes;
    }
    if (k === 'queue') {
      if (d > 5500) return null;
      const speed = d < 1500 ? 30 : d < 3200 ? 40 : 50;
      set(() => ({ aspect: 'speed', speed }));
      return lanes;
    }
    if (d > 6500) return null;
    const speed = d < 2500 ? 40 : d < 4500 ? 50 : 60;
    const mergeDir = (l) => (Math.min(...sc.lanes) === 1 ? 'merge_right' : 'merge_left');
    set((l) => {
      if (closed.has(l) && d < 1300) return { aspect: 'closed' };
      if (closed.has(l) && d < 2500) return { aspect: mergeDir(l) };
      return { aspect: 'speed', speed };
    });
    return lanes;
  }

  return {
    name: 'demo',
    label: 'Demo simulator',
    demo: true,
    intervalMs: 0,
    async poll() { return this.events(); },
    events() {
      const t = now();
      for (const [k, v] of store) if (v.expires < t) store.delete(k);
      return [...store.values()].map((v) => v.event);
    },
    /** Make sure the motorways on this route have gantries + any scenario. */
    prepareRoute(coordsOrLine, steps) {
      const line = coordsOrLine instanceof RouteLine ? coordsOrLine : new RouteLine(coordsOrLine);
      for (const run of motorwayRuns(line, steps)) {
        const sc = scenarioFor(run, line);
        const hz = sc ? line.snap(sc.location, { maxDist: 40, bearing: sc.bearing, maxBearingDiff: 45 }) : null;
        for (let a = run.from + 400; a < run.to - 200; a += GANTRY_SPACING) {
          const loc = line.pointAt(a);
          const brg = line.bearingAt(a);
          const cell = `${run.ref}:${dirBucket(brg)}:${Math.round(loc[0] / 0.006)}:${Math.round(loc[1] / 0.006)}`;
          const id = `demo:g:${cell}`;
          if (store.has(id)) continue;
          // Don't put a gantry within 500 m of an existing one on the same carriageway.
          const near = [...store.values()].some(({ event: e }) => e.type === 'signal' && e.road === run.ref &&
            bearingDiff(e.bearing, brg) < 45 && Math.abs(e.location[0] - loc[0]) < 0.008 && Math.abs(e.location[1] - loc[1]) < 0.005);
          if (near) continue;
          const lanes = hz ? gantryFor(sc, hz.along - a) : null;
          const n = sc?.lanesTotal || 3;
          const set = lanes || Array.from({ length: n }, (_, i) => ({ lane: i + 1, aspect: 'blank' }));
          const speeds = set.filter((l) => l.speed).map((l) => l.speed);
          const closed = set.filter((l) => l.aspect === 'closed').map((l) => l.lane);
          const speedLimit = speeds.length ? Math.min(...speeds) : null;
          put({
            id,
            source: 'demo',
            type: 'signal',
            severity: closed.length >= 2 ? 'severe' : closed.length ? 'high' : speedLimit && speedLimit <= 40 ? 'high' : speedLimit && speedLimit <= 50 ? 'medium' : 'low',
            road: run.ref,
            bearing: brg,
            location: loc,
            title: signalTitle(speedLimit, closed),
            lanesClosed: closed,
            speedLimit,
            signal: { lanes: set, mandatory: closed.length > 0 },
            updated: new Date(now()).toISOString(),
          });
        }
        // A message sign ~8 km upstream of the hazard.
        if (hz && hz.along - run.from > 8500) {
          const a = hz.along - 8000;
          const loc = line.pointAt(a);
          const id = `demo:vms:${sc.id}`;
          if (!store.has(id)) put({
            id, source: 'demo', type: 'vms', severity: 'medium', road: run.ref, bearing: line.bearingAt(a), location: loc,
            title: `Sign: ${sc.def.vms(5).join(' / ')}`, description: sc.def.vms(5).join(' / '), vmsText: sc.def.vms(5),
            updated: new Date(now()).toISOString(),
          });
        }
      }
    },
  };
}
