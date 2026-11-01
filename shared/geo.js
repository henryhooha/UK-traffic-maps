// Geometry helpers shared by the server and the browser (plain ES module, no deps).
// Coordinates are [lon, lat] (GeoJSON order) unless a function says otherwise.

const R = 6371008.8; // mean Earth radius, metres
const toRad = (d) => (d * Math.PI) / 180;
const toDeg = (r) => (r * 180) / Math.PI;

export const METRES_PER_MILE = 1609.344;
export const mphToMs = (mph) => mph * 0.44704;

export function haversine([lon1, lat1], [lon2, lat2]) {
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** Initial bearing from a to b, degrees clockwise from north, 0..360. */
export function bearing([lon1, lat1], [lon2, lat2]) {
  const p1 = toRad(lat1);
  const p2 = toRad(lat2);
  const dl = toRad(lon2 - lon1);
  const y = Math.sin(dl) * Math.cos(p2);
  const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

/** Smallest absolute difference between two bearings, 0..180. */
export function bearingDiff(a, b) {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
}

/** Point reached travelling `dist` metres from `start` on `brg` degrees. */
export function destination([lon, lat], brg, dist) {
  const d = dist / R;
  const t = toRad(brg);
  const p1 = toRad(lat);
  const l1 = toRad(lon);
  const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(t));
  const l2 = l1 + Math.atan2(Math.sin(t) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
  return [((toDeg(l2) + 540) % 360) - 180, toDeg(p2)];
}

/** Cumulative distance (m) at each vertex of a [lon,lat][] line. */
export function cumulativeDistances(coords) {
  const cum = new Array(coords.length);
  cum[0] = 0;
  for (let i = 1; i < coords.length; i++) cum[i] = cum[i - 1] + haversine(coords[i - 1], coords[i]);
  return cum;
}

// Local equirectangular projection around a reference latitude — accurate to
// well under a metre over the few hundred metres we care about when snapping.
function localXY([lon, lat], refLat) {
  const k = Math.cos(toRad(refLat));
  return [toRad(lon) * R * k, toRad(lat) * R];
}

/** Project point p onto segment a-b. Returns {t (0..1), dist (m)}. */
export function projectOnSegment(p, a, b) {
  const ref = p[1];
  const [px, py] = localXY(p, ref);
  const [ax, ay] = localXY(a, ref);
  const [bx, by] = localXY(b, ref);
  const dx = bx - ax;
  const dy = by - ay;
  const len2 = dx * dx + dy * dy;
  let t = len2 === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  const cx = ax + t * dx;
  const cy = ay + t * dy;
  return { t, dist: Math.hypot(px - cx, py - cy) };
}

/**
 * A route line prepared for repeated snapping: cumulative distances, per-segment
 * bearings and a coarse grid index so projecting thousands of events is cheap.
 */
export class RouteLine {
  constructor(coords, cellDeg = 0.01) {
    this.coords = coords;
    this.cum = cumulativeDistances(coords);
    this.length = this.cum[this.cum.length - 1] || 0;
    this.cellDeg = cellDeg;
    this.bearings = [];
    this.grid = new Map();
    let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity;
    for (let i = 0; i < coords.length - 1; i++) {
      this.bearings.push(bearing(coords[i], coords[i + 1]));
      const [lo1, la1] = coords[i];
      const [lo2, la2] = coords[i + 1];
      const x0 = Math.floor(Math.min(lo1, lo2) / cellDeg), x1 = Math.floor(Math.max(lo1, lo2) / cellDeg);
      const y0 = Math.floor(Math.min(la1, la2) / cellDeg), y1 = Math.floor(Math.max(la1, la2) / cellDeg);
      for (let x = x0; x <= x1; x++)
        for (let y = y0; y <= y1; y++) {
          const key = x + ':' + y;
          let arr = this.grid.get(key);
          if (!arr) this.grid.set(key, (arr = []));
          arr.push(i);
        }
    }
    for (const [lo, la] of coords) {
      minLon = Math.min(minLon, lo); maxLon = Math.max(maxLon, lo);
      minLat = Math.min(minLat, la); maxLat = Math.max(maxLat, la);
    }
    this.bbox = [minLon, minLat, maxLon, maxLat];
  }

  /**
   * Snap a point onto the route.
   * @param {[number,number]} p  [lon,lat]
   * @param {object} opts maxDist (m), bearing (deg, travel direction of the thing
   *   being snapped, optional), maxBearingDiff (deg), minAlong (m, ignore matches
   *   before this distance — useful for "only ahead of me").
   * @returns {{along:number, offset:number, segment:number, bearing:number}|null}
   */
  snap(p, { maxDist = 50, bearing: brg = null, maxBearingDiff = 50, minAlong = -Infinity, maxAlong = Infinity } = {}) {
    const cx = Math.floor(p[0] / this.cellDeg);
    const cy = Math.floor(p[1] / this.cellDeg);
    const seen = new Set();
    let best = null;
    for (let x = cx - 1; x <= cx + 1; x++)
      for (let y = cy - 1; y <= cy + 1; y++) {
        const segs = this.grid.get(x + ':' + y);
        if (!segs) continue;
        for (const i of segs) {
          if (seen.has(i)) continue;
          seen.add(i);
          if (brg != null && bearingDiff(brg, this.bearings[i]) > maxBearingDiff) continue;
          const { t, dist } = projectOnSegment(p, this.coords[i], this.coords[i + 1]);
          if (dist > maxDist) continue;
          const along = this.cum[i] + t * (this.cum[i + 1] - this.cum[i]);
          if (along < minAlong || along > maxAlong) continue;
          if (!best || dist < best.offset - 1e-6) best = { along, offset: dist, segment: i, bearing: this.bearings[i] };
        }
      }
    return best;
  }

  /** [lon,lat] at a distance along the route. */
  pointAt(along) {
    if (along <= 0) return this.coords[0];
    if (along >= this.length) return this.coords[this.coords.length - 1];
    let lo = 0, hi = this.cum.length - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (this.cum[mid] <= along) lo = mid; else hi = mid;
    }
    const seg = this.cum[hi] - this.cum[lo];
    const t = seg === 0 ? 0 : (along - this.cum[lo]) / seg;
    const [a, b] = [this.coords[lo], this.coords[hi]];
    return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
  }

  /** Travel bearing at a distance along the route. */
  bearingAt(along) {
    let lo = 0, hi = this.cum.length - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (this.cum[mid] <= along) lo = mid; else hi = mid;
    }
    return this.bearings[Math.min(lo, this.bearings.length - 1)] ?? 0;
  }

  /** Sub-line between two distances along the route. */
  slice(from, to) {
    const out = [this.pointAt(from)];
    for (let i = 0; i < this.coords.length; i++) if (this.cum[i] > from && this.cum[i] < to) out.push(this.coords[i]);
    out.push(this.pointAt(to));
    return out;
  }
}

/** Compass words → nominal bearing (deg). Accepts "northbound", "NB", "north", "clockwise" (null). */
export function directionToBearing(dir) {
  if (!dir) return null;
  const d = String(dir).toLowerCase().replace(/[^a-z]/g, '');
  const table = {
    n: 0, nb: 0, north: 0, northbound: 0,
    ne: 45, northeast: 45, northeastbound: 45,
    e: 90, eb: 90, east: 90, eastbound: 90,
    se: 135, southeast: 135, southeastbound: 135,
    s: 180, sb: 180, south: 180, southbound: 180,
    sw: 225, southwest: 225, southwestbound: 225,
    w: 270, wb: 270, west: 270, westbound: 270,
    nw: 315, northwest: 315, northwestbound: 315,
  };
  return d in table ? table[d] : null;
}

export const fmtMiles = (m) => {
  const mi = m / METRES_PER_MILE;
  if (mi < 0.1) return `${Math.round(m)} m`;
  return mi < 10 ? `${mi.toFixed(1)} mi` : `${Math.round(mi)} mi`;
};
