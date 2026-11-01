// Route planning on top of OSRM: analyse each candidate against live events,
// score it (drive time + expected hazard delay) and generate diversions.

import { RouteLine, destination, haversine } from '../../shared/geo.js';
import { buildRoute, analyseRoute, sameHazard } from './analyse.js';
import { osrmRoute } from './osrm.js';
import { lookupJunctionNames } from './junctions.js';

export function createPlanner({ config, providers, fetchImpl = fetch }) {
  const cache = new Map(); // routeId -> route (with coords)
  const MAX_CACHE = 200;
  let seq = 0;

  function remember(route) {
    route.id = `r${Date.now().toString(36)}${(seq++).toString(36)}`;
    cache.set(route.id, route);
    if (cache.size > MAX_CACHE) cache.delete(cache.keys().next().value);
    return route.id;
  }

  async function analyse(route) {
    if (!route.line) route.line = buildRoute(new RouteLine(route.coords), route.steps);
    providers.demo?.prepareRoute(route.line, route.steps);
    if (!route.junctionsResolved) {
      route.junctions = await lookupJunctionNames(route.exits, config, fetchImpl).catch(() => route.exits);
      route.junctionsResolved = true;
    }
    const analysis = analyseRoute(route.line, providers.events(), { junctions: route.junctions });
    route.analysis = analysis;
    route.score = route.duration / 60 + analysis.totalDelayMinutes;
    return analysis;
  }

  const publicRoute = (r) => ({
    id: r.id,
    coords: r.coords,
    distance: r.distance,
    duration: r.duration,
    summary: r.summary,
    steps: r.steps,
    analysis: r.analysis,
    score: r.score,
    etaMinutes: Math.round(r.score),
    avoids: r.avoids || null,
    label: r.label || null,
  });

  async function plan(from, to) {
    const routes = await osrmRoute(config.osrmUrl, [from, to], { alternatives: 2, fetchImpl });
    for (const r of routes) { remember(r); await analyse(r); }
    routes.forEach((r, i) => (r.label = i === 0 ? 'Fastest by distance/time' : `Alternative ${i}`));
    const best = [...routes].sort((a, b) => a.score - b.score)[0];
    best.label = 'Recommended';
    return routes.sort((a, b) => a.score - b.score).map(publicRoute);
  }

  async function reanalyse(routeId) {
    const r = cache.get(routeId);
    if (!r) return null;
    await analyse(r);
    return { id: r.id, analysis: r.analysis, score: r.score, etaMinutes: Math.round(r.score) };
  }

  /**
   * Find ways round a hazard on route `routeId`, starting from `position`.
   * Candidates: OSRM alternatives from here, plus routes forced through via-points
   * offset to either side of the hazard.
   */
  async function divert(routeId, hazardId, position, destinationPt) {
    const cur = cache.get(routeId);
    if (!cur) throw new Error('Route expired — please re-plan');
    const hazard = cur.analysis?.items.find((i) => i.id === hazardId);
    if (!hazard) throw new Error('That hazard is no longer on your route');
    const to = destinationPt || cur.coords[cur.coords.length - 1];
    const from = position || cur.coords[0];

    // Baseline: continue on the current route from where you are.
    const here = cur.line.snap(from, { maxDist: 2000 });
    const doneMins = here ? (here.along / cur.distance) * (cur.duration / 60) : 0;
    const remainingDelay = cur.analysis.items.filter((i) => i.kind !== 'vms' && i.end > (here?.along ?? 0)).reduce((s, i) => s + (i.delayMinutes || 0), 0);
    const stay = { minutes: Math.round(cur.duration / 60 - doneMins + remainingDelay), delayMinutes: Math.round(remainingDelay) };

    const candidates = [];
    const tryRoute = async (waypoints, label) => {
      try {
        const rs = await osrmRoute(config.osrmUrl, waypoints, { alternatives: waypoints.length === 2 ? 2 : 0, fetchImpl });
        for (const r of rs) { r.label = label; candidates.push(r); }
      } catch { /* a via-point may be unroutable (sea, etc.) */ }
    };

    await tryRoute([from, to], 'Alternative from here');
    const centre = hazard.location;
    const brg = cur.line.bearingAt((hazard.start + hazard.end) / 2);
    for (const off of [5000, 10000]) {
      for (const side of [90, -90]) {
        const via = destination(centre, brg + side, off);
        await tryRoute([from, via, to], `Detour ${side > 0 ? 'east/right' : 'west/left'} of the ${hazard.road || 'hazard'}`);
      }
    }

    const seen = [];
    const options = [];
    for (const r of candidates) {
      if (seen.some((s) => Math.abs(s.distance - r.distance) < r.distance * 0.01 && Math.abs(s.duration - r.duration) < 60)) continue;
      seen.push(r);
      remember(r);
      await analyse(r);
      const hits = r.analysis.items.some((i) => sameHazard(hazard, i));
      if (hits) continue;
      // Reject silly detours: start too far from here or loops.
      if (haversine(r.coords[0], from) > 3000) continue;
      r.avoids = hazard.headline;
      options.push(r);
    }
    options.sort((a, b) => a.score - b.score);
    // Same roads, slightly different streets → keep the quicker one only.
    const distinct = [];
    for (const o of options) if (!distinct.some((d) => d.summary === o.summary && Math.abs(d.distance - o.distance) < o.distance * 0.08)) distinct.push(o);
    options.length = 0; options.push(...distinct);
    return {
      hazard: { id: hazard.id, headline: hazard.headline },
      stay,
      options: options.slice(0, 3).map((r) => ({ ...publicRoute(r), deltaMinutes: Math.round(r.score - stay.minutes) })),
    };
  }

  return { plan, reanalyse, divert, get: (id) => cache.get(id) };
}
