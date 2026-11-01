// "Road ahead" analysis: take a route and the live event set, work out which
// events are actually on *your* carriageway, how far along the route they are,
// group consecutive smart-motorway gantries into restriction zones, estimate
// the delay, and find the last junction where you could still leave.

import { RouteLine, METRES_PER_MILE, haversine } from '../../shared/geo.js';
import { SEVERITY_RANK, maxSeverity, normRoad } from './events.js';

const FREE_FLOW_MPH = 70;
const ZONE_GAP_M = 3000; // gantries further apart than this start a new zone
const SIGNAL_TAIL_M = 800; // a gantry setting applies until roughly the next gantry

const SNAP = {
  signal: { maxDist: 45, withBearing: 50, withoutBearing: 20 },
  vms: { maxDist: 60, withBearing: 50, withoutBearing: 25 },
  default: { maxDist: 120, withBearing: 70, withoutBearing: 60 },
};

/**
 * @param {Array<[number,number]>} coords route polyline [lon,lat]
 * @param {Array} steps optional [{from, to, ref, name}] distance ranges along the route
 */
export function buildRoute(coords, steps = []) {
  const line = coords instanceof RouteLine ? coords : new RouteLine(coords);
  const sorted = [...steps].sort((a, b) => a.from - b.from);
  line.roadAt = (along) => {
    for (const s of sorted) if (along >= s.from && along <= s.to) return s;
    return null;
  };
  return line;
}

function snapEvent(line, ev) {
  const cfg = SNAP[ev.type] || SNAP.default;
  const hasBearing = ev.bearing != null;
  const opts = hasBearing
    ? { maxDist: cfg.maxDist, bearing: ev.bearing, maxBearingDiff: cfg.withBearing }
    : { maxDist: cfg.withoutBearing };
  const hit = line.snap(ev.location, opts);
  if (!hit) return null;
  // Road check: a gantry on the M1 shouldn't match the A-road on a bridge above it.
  const evRoad = normRoad(ev.road);
  const step = line.roadAt?.(hit.along);
  const routeRoad = normRoad(step?.ref?.split(';')[0]);
  if (evRoad && routeRoad && evRoad !== routeRoad && hit.offset > 12) {
    const anyRef = (step?.ref || '').split(/[;,]/).map(normRoad);
    if (!anyRef.includes(evRoad)) return null;
  }
  let endAlong = null;
  if (ev.end) {
    const endHit = line.snap(ev.end, { ...opts, maxDist: opts.maxDist * 1.5, minAlong: hit.along });
    if (endHit) endAlong = endHit.along;
  }
  return { along: hit.along, endAlong, offset: hit.offset, directionKnown: hasBearing };
}

function heuristicDelay(ev) {
  if (ev.delayMinutes != null) return ev.delayMinutes;
  switch (ev.type) {
    case 'closure': return 20;
    case 'accident': return ev.severity === 'severe' ? 25 : ev.severity === 'high' ? 12 : 6;
    case 'lane_closure': return 3 + 3 * (ev.lanesClosed?.length || 1);
    case 'congestion': return ev.severity === 'severe' ? 20 : 8;
    case 'incident': return ev.severity === 'high' ? 8 : 3;
    case 'roadworks': return ev.lanesClosed?.length ? 4 : 2;
    case 'weather': return 3;
    default: return 0;
  }
}

/** Extra minutes from driving `metres` at `mph` instead of free flow. */
const slowdownMinutes = (metres, mph) =>
  !mph || mph >= FREE_FLOW_MPH ? 0 : (metres / METRES_PER_MILE) * (60 / mph - 60 / FREE_FLOW_MPH);

const isRestricted = (g) => (g.speedLimit && g.speedLimit < FREE_FLOW_MPH) || g.lanesClosed?.length > 0;

function buildZones(gantries, line) {
  const zones = [];
  let cur = null;
  for (let i = 0; i < gantries.length; i++) {
    const g = gantries[i];
    if (!isRestricted(g)) {
      if (cur) { cur.end = g.along; zones.push(cur); cur = null; }
      continue;
    }
    if (cur && (g.along - cur.gantries[cur.gantries.length - 1].along > ZONE_GAP_M || normRoad(g.road) !== normRoad(cur.road))) {
      cur.end = cur.gantries[cur.gantries.length - 1].along + SIGNAL_TAIL_M;
      zones.push(cur);
      cur = null;
    }
    if (!cur) cur = { start: g.along, gantries: [], road: g.road };
    cur.gantries.push(g);
  }
  if (cur) { cur.end = Math.min(line.length, cur.gantries[cur.gantries.length - 1].along + SIGNAL_TAIL_M); zones.push(cur); }

  return zones.map((z, idx) => {
    const gs = z.gantries;
    const speeds = gs.map((g) => g.speedLimit).filter(Boolean);
    const minSpeed = speeds.length ? Math.min(...speeds) : null;
    const closedSet = new Set(gs.flatMap((g) => g.lanesClosed || []));
    const firstClosure = gs.find((g) => g.lanesClosed?.length);
    const firstMerge = gs.find((g) => g.signal?.lanes?.some((l) => /merge/.test(l.aspect)));
    let delay = 0;
    for (let i = 0; i < gs.length; i++) {
      const segEnd = i + 1 < gs.length ? gs[i + 1].along : z.end;
      delay += slowdownMinutes(segEnd - gs[i].along, gs[i].speedLimit);
    }
    delay += closedSet.size ? 1.5 * closedSet.size : 0;
    const severity = gs.reduce((s, g) => maxSeverity(s, g.severity), 'low');
    const details = [];
    if (minSpeed) details.push(`Speed limits down to ${minSpeed} mph (${speeds.join(' → ')})`);
    if (firstMerge && (!firstClosure || firstMerge.along < firstClosure.along))
      details.push(`Merge arrows from ${fmt(firstMerge.along - z.start)} into the zone`);
    if (firstClosure) details.push(`${closedSet.size > 1 ? 'Lanes' : 'Lane'} ${[...closedSet].sort().join(' & ')} closed (red X) — ${fmt(firstClosure.along - z.start)} into the zone`);
    details.push(`${gs.length} gantr${gs.length === 1 ? 'y' : 'ies'} set over ${fmt(z.end - z.start)}`);
    return {
      id: `zone:${normRoad(z.road) || 'road'}:${Math.round(z.start)}:${idx}`,
      kind: 'zone',
      type: closedSet.size ? 'lane_closure' : 'speed_restriction',
      severity,
      road: z.road,
      start: z.start,
      end: z.end,
      speedLimit: minSpeed,
      lanesClosed: [...closedSet].sort(),
      closureAt: firstClosure ? firstClosure.along : null,
      headline: zoneHeadline(z.road, minSpeed, [...closedSet].sort()),
      details,
      delayMinutes: Math.round(delay * 10) / 10,
      gantries: gs.map((g) => ({ id: g.id, along: g.along, location: g.location, signal: g.signal, speedLimit: g.speedLimit, lanesClosed: g.lanesClosed, source: g.source })),
      events: [],
      location: line.pointAt(z.start),
    };
  });
}

function zoneHeadline(road, speed, closed) {
  const r = road ? `${road}: ` : '';
  if (closed.length && speed) return `${r}${closed.length > 1 ? 'Lanes' : 'Lane'} ${closed.join(' & ')} closed, ${speed} mph limit`;
  if (closed.length) return `${r}${closed.length > 1 ? 'Lanes' : 'Lane'} ${closed.join(' & ')} closed`;
  return `${r}${speed} mph limit on the gantries`;
}

const fmt = (m) => {
  const mi = m / METRES_PER_MILE;
  return mi < 0.2 ? `${Math.round(m)} m` : `${mi.toFixed(1)} mi`;
};

/**
 * Analyse a route.
 * @param {RouteLine} line from buildRoute()
 * @param {Array} events normalised events
 * @param {object} opts { junctions: [{ref,name,location}] }
 * @returns {{ items, gantries, vms, matchedCount, totalDelayMinutes }}
 */
export function analyseRoute(line, events, { junctions = [] } = {}) {
  const [minLon, minLat, maxLon, maxLat] = line.bbox;
  const pad = 0.02;
  const candidates = events.filter(({ location: [lo, la] }) =>
    lo >= minLon - pad && lo <= maxLon + pad && la >= minLat - pad && la <= maxLat + pad);

  const matched = [];
  for (const ev of candidates) {
    const s = snapEvent(line, ev);
    if (s) matched.push({ ...ev, along: s.along, endAlong: s.endAlong, offset: s.offset, directionKnown: s.directionKnown });
  }
  matched.sort((a, b) => a.along - b.along);

  const gantries = matched.filter((e) => e.type === 'signal');
  const vms = matched.filter((e) => e.type === 'vms');
  const others = matched.filter((e) => e.type !== 'signal' && e.type !== 'vms');

  const zones = buildZones(gantries, line);

  // Attach incidents/VMS that explain a zone (inside it or just past it).
  const standalone = [];
  for (const ev of others) {
    const z = zones.find((z) => ev.along >= z.start - 300 && ev.along <= z.end + 2500);
    if (z) {
      z.events.push(slim(ev));
      z.severity = maxSeverity(z.severity, ev.severity);
      z.end = Math.max(z.end, ev.endAlong ?? ev.along);
      if (ev.delayMinutes != null && ev.delayMinutes > z.delayMinutes) z.delayMinutes = ev.delayMinutes;
      z.cause = z.cause || ev.title;
    } else standalone.push(ev);
  }
  for (const v of vms) {
    // A VMS message usually warns of what's downstream; attach it to the next zone within ~10 miles.
    const z = zones.find((z) => v.along <= z.end && z.start - v.along < 16000);
    if (z) z.events.push(slim(v));
  }

  const eventItems = standalone.map((ev) => ({
    id: ev.id,
    kind: 'event',
    type: ev.type,
    severity: ev.severity,
    road: ev.road,
    start: ev.along,
    end: ev.endAlong ?? ev.along,
    speedLimit: ev.speedLimit ?? null,
    lanesClosed: ev.lanesClosed || [],
    headline: ev.title,
    details: [ev.description, ev.lanesClosed?.length ? `Lanes closed: ${ev.lanesClosed.map((l) => (l === 0 ? 'hard shoulder' : l)).join(', ')}${ev.lanesTotal ? ` of ${ev.lanesTotal}` : ''}` : null,
      ev.endTime ? `Expected until ${new Date(ev.endTime).toLocaleString('en-GB', { dateStyle: 'short', timeStyle: 'short' })}` : null,
      ev.directionKnown ? null : 'Direction not given in feed — may be on the opposite carriageway'].filter(Boolean),
    delayMinutes: heuristicDelay(ev),
    delayIsEstimate: ev.delayMinutes == null,
    events: [slim(ev)],
    gantries: [],
    location: ev.location,
  }));

  // Standalone VMS messages (not tied to a zone) are still worth showing.
  const vmsItems = vms
    .filter((v) => !zones.some((z) => z.events.some((e) => e.id === v.id)))
    .map((v) => ({
      id: v.id, kind: 'vms', type: 'vms', severity: v.severity, road: v.road, start: v.along, end: v.along,
      headline: v.vmsText?.join(' · ') || v.title, details: [], delayMinutes: 0, events: [slim(v)], gantries: [], location: v.location,
      vmsText: v.vmsText,
    }));

  const items = [...zones, ...eventItems, ...vmsItems].sort((a, b) => a.start - b.start);

  // Junctions on the route → "last exit before".
  const exits = junctions
    .map((j) => ({ ...j, hit: line.snap(j.location, { maxDist: 35 }) }))
    .filter((j) => j.hit)
    .map((j) => ({ ref: j.ref, name: j.name, along: j.hit.along, location: j.location }))
    .sort((a, b) => a.along - b.along);
  for (const it of items) {
    const before = exits.filter((x) => x.along < it.start - 250);
    it.exitBefore = before.length ? before[before.length - 1] : null;
    it.exitsBefore = before.slice(-3).reverse();
  }

  const totalDelayMinutes = Math.round(items.reduce((s, i) => s + (i.kind === 'vms' ? 0 : i.delayMinutes || 0), 0));
  return {
    items,
    exits,
    gantries: gantries.map((g) => ({ id: g.id, along: g.along, location: g.location, signal: g.signal, speedLimit: g.speedLimit, lanesClosed: g.lanesClosed, road: g.road, source: g.source })),
    matchedCount: matched.length,
    totalDelayMinutes,
    worstSeverity: items.reduce((s, i) => (i.kind === 'vms' ? s : maxSeverity(s, i.severity)), 'low'),
  };
}

function slim(ev) {
  const { id, source, type, severity, title, description, road, direction, location, along, lanesClosed, speedLimit, delayMinutes, vmsText, signal, startTime, endTime, updated } = ev;
  return { id, source, type, severity, title, description, road, direction, location, along, lanesClosed, speedLimit, delayMinutes, vmsText, signal, startTime, endTime, updated };
}

/** Is item `a` (from one analysis) the same hazard as `b`? Used to see if a diversion avoids it. */
export function sameHazard(a, b) {
  if (a.id === b.id) return true;
  const evIds = new Set(a.events.map((e) => e.id).concat(a.gantries.map((g) => g.id)));
  if (b.events.some((e) => evIds.has(e.id)) || b.gantries.some((g) => evIds.has(g.id))) return true;
  return a.location && b.location && haversine(a.location, b.location) < 400 && normRoad(a.road) === normRoad(b.road);
}

export { SEVERITY_RANK };
