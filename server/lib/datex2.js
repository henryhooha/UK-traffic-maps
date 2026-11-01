// Tolerant DATEX II (v2.3 and v3.x) parser.
//
// National Highways (NTIS), Traffic Scotland and Traffic Wales all publish in
// DATEX II but the exact profile differs per feed and version, so rather than
// binding to one schema we walk the XML tree looking for the well-known
// element names: situationRecord, vmsUnit / vmsControllerStatus, vmsUnitRecord /
// vmsControllerRecord, pointCoordinates, etc.
//
// Speeds in DATEX II are km/h; we convert to posted mph.

import { XMLParser } from 'fast-xml-parser';
import { directionToBearing } from '../../shared/geo.js';
import { kmhToPostedMph, lanesFromText, roadFromText, maxSeverity } from './events.js';

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: '@_',
  removeNSPrefix: true,
  textNodeName: '#text',
  parseTagValue: false,
  isArray: (name) =>
    [
      'situation', 'situationRecord', 'vmsUnit', 'vms', 'vmsTextLine', 'vmsPictogram',
      'vmsUnitRecord', 'vmsRecord', 'vmsControllerStatus', 'vmsStatus', 'vmsControllerRecord',
      'vmsPictogramDisplayArea', 'values', 'value', 'lane', 'laneNumber', 'textPage', 'pictogram',
      'vmsPictogramDisplayAreaIndex', 'comment', 'generalPublicComment',
    ].includes(name),
});

export function parseXml(xml) {
  return parser.parse(xml);
}

// ---------- generic tree helpers ----------

const asArray = (x) => (x == null ? [] : Array.isArray(x) ? x : [x]);
const text = (x) => {
  if (x == null) return null;
  if (typeof x === 'string' || typeof x === 'number') return String(x);
  if (Array.isArray(x)) return text(x[0]);
  if (typeof x === 'object' && '#text' in x) return String(x['#text']);
  return null;
};

/** Depth-first collect every node whose key is in `names`. Returns [{key,node,parentKey}]. */
function collect(root, names, out = [], parentKey = null) {
  if (root == null || typeof root !== 'object') return out;
  if (Array.isArray(root)) {
    for (const r of root) collect(r, names, out, parentKey);
    return out;
  }
  for (const [k, v] of Object.entries(root)) {
    if (k.startsWith('@_')) continue;
    if (names.has(k)) for (const n of asArray(v)) out.push({ key: k, node: n, parentKey });
    else collect(v, names, out, k);
  }
  return out;
}

/** First descendant value for any of the given keys (breadth-first). */
function find(root, ...keys) {
  const q = [root];
  while (q.length) {
    const n = q.shift();
    if (n == null || typeof n !== 'object') continue;
    if (Array.isArray(n)) { q.push(...n); continue; }
    for (const k of keys) if (k in n) return n[k];
    for (const [k, v] of Object.entries(n)) if (!k.startsWith('@_')) q.push(v);
  }
  return undefined;
}

function findAll(root, key, out = []) {
  if (root == null || typeof root !== 'object') return out;
  if (Array.isArray(root)) { root.forEach((r) => findAll(r, key, out)); return out; }
  for (const [k, v] of Object.entries(root)) {
    if (k === key) out.push(...asArray(v));
    else if (!k.startsWith('@_')) findAll(v, key, out);
  }
  return out;
}

/** Multilingual string → first English-ish value. */
function mlString(node) {
  if (node == null) return null;
  const vals = findAll(node, 'value');
  if (vals.length) {
    const en = vals.find((v) => typeof v === 'object' && /^en/i.test(v['@_lang'] || '')) || vals[0];
    return text(en);
  }
  return text(node);
}

function coordsOf(node) {
  if (!node || typeof node !== 'object') return null;
  const lat = Number(text(node.latitude));
  const lon = Number(text(node.longitude));
  return Number.isFinite(lat) && Number.isFinite(lon) ? [lon, lat] : null;
}

/**
 * Work out start/end/bearing/direction for a location reference.
 * Handles point (pointByCoordinates), linear TPEG (from/to) and v3 locationForDisplay.
 */
export function extractLocation(node) {
  let start = null, end = null, brg = null;
  const from = find(node, 'from');
  const to = find(node, 'to');
  if (from) start = coordsOf(find(from, 'pointCoordinates'));
  if (to) end = coordsOf(find(to, 'pointCoordinates'));
  if (!start) start = coordsOf(find(node, 'pointCoordinates'));
  if (!start) start = coordsOf(find(node, 'locationForDisplay'));
  const b = text(find(node, 'bearing'));
  if (b != null && b !== '' && Number.isFinite(Number(b))) brg = Number(b);
  const dir = text(find(node, 'tpegDirection', 'directionBoundOnLinearSection', 'directionBound', 'carriagewayDirection', 'direction', 'tpegDirectionRoad'));
  if (brg == null) brg = directionToBearing(dir);
  const road = text(find(node, 'roadNumber', 'roadName')) || null;
  return { start, end: end && start && (end[0] !== start[0] || end[1] !== start[1]) ? end : null, bearing: brg, direction: dir, road };
}

// ---------- situations ----------

const TYPE_MAP = {
  Accident: 'accident',
  VehicleObstruction: 'incident',
  GeneralObstruction: 'incident',
  AnimalPresenceObstruction: 'incident',
  EnvironmentalObstruction: 'incident',
  InfrastructureDamageObstruction: 'incident',
  EquipmentOrSystemFault: 'incident',
  AbnormalTraffic: 'congestion',
  MaintenanceWorks: 'roadworks',
  ConstructionWorks: 'roadworks',
  Roadworks: 'roadworks',
  RoadOrCarriagewayOrLaneManagement: 'lane_closure',
  SpeedManagement: 'speed_restriction',
  PoorEnvironmentConditions: 'weather',
  WeatherRelatedRoadConditions: 'weather',
  ReroutingManagement: 'closure',
  NetworkManagement: 'closure',
  DisturbanceActivity: 'incident',
  PublicEvent: 'incident',
};

const SEV_MAP = { highest: 'severe', high: 'high', medium: 'medium', low: 'low', lowest: 'low', none: 'low', unknown: 'medium' };

function laneNumbers(rec) {
  const lanes = new Set();
  for (const v of [...findAll(rec, 'lane'), ...findAll(rec, 'laneNumber'), ...findAll(rec, 'laneUsage')]) {
    const s = text(typeof v === 'object' && !('#text' in v) ? find(v, 'laneNumber', 'lane') ?? v : v);
    if (!s) continue;
    const m = s.match(/lane\s*(\d+)/i) || s.match(/^(\d+)$/);
    if (m) lanes.add(Number(m[1]));
    else if (/hardShoulder/i.test(s)) lanes.add(0);
  }
  return [...lanes].sort((a, b) => a - b);
}

export function parseSituations(doc, source = 'datex2') {
  const events = [];
  const situations = collect(doc, new Set(['situation']));
  const recs = situations.length
    ? situations.flatMap(({ node }) => asArray(node.situationRecord).map((r) => ({ r, sit: node })))
    : collect(doc, new Set(['situationRecord'])).map(({ node }) => ({ r: node, sit: null }));

  for (const { r, sit } of recs) {
    const xsiType = (r['@_type'] || '').replace(/^.*:/, '');
    const type = TYPE_MAP[xsiType] || 'incident';
    const loc = extractLocation(r.groupOfLocations || r.locationReference || r);
    if (!loc.start) continue;

    const comments = asArray(r.generalPublicComment).map((c) => mlString(c.comment ?? c)).filter(Boolean);
    const cause = text(find(r, 'causeType', 'detailedCauseType'));
    const subType =
      text(find(r, 'accidentType', 'vehicleObstructionType', 'obstructionType', 'abnormalTrafficType',
        'roadMaintenanceType', 'constructionWorkType', 'roadOrCarriagewayOrLaneManagementType',
        'speedManagementType', 'poorEnvironmentType', 'reroutingManagementType')) || null;
    const description = comments.join(' ') || null;

    const fromText = lanesFromText(description || '');
    const lanes = laneNumbers(r);
    const lanesClosed = lanes.length ? lanes : fromText.lanesClosed;
    const lanesRestricted = Number(text(find(r, 'numberOfLanesRestricted')));
    const original = Number(text(find(r, 'originalNumberOfLanes'))) || fromText.lanesTotal || null;

    let speedLimit = null;
    const sl = text(find(r, 'temporarySpeedLimit', 'speedLimit'));
    if (sl && Number(sl) > 0) speedLimit = kmhToPostedMph(sl);

    let delayMinutes = null;
    const delay = text(find(r, 'delayTimeValue', 'delaysTimeValue'));
    if (delay && Number(delay) > 0) delayMinutes = Math.round(Number(delay) / 60);

    const sevRaw = (text(r.severity) || text(sit?.overallSeverity) || '').toLowerCase();
    let severity = SEV_MAP[sevRaw] || (type === 'accident' ? 'high' : 'medium');
    const closureType = (subType || '').toLowerCase();
    const fullClosure = fromText.fullClosure || /carriagewayclosures|roadclosed|closed$/.test(closureType);
    if (fullClosure) severity = 'severe';
    if (lanesClosed.length >= 2) severity = maxSeverity(severity, 'high');

    const road = loc.road || roadFromText(description);
    const title = [
      humanType(type, subType, fullClosure),
      road,
      loc.direction ? prettyDir(loc.direction) : null,
    ].filter(Boolean).join(' · ');

    events.push({
      id: `${source}:${r['@_id'] || sit?.['@_id'] || Math.random().toString(36).slice(2)}`,
      source,
      type: fullClosure ? 'closure' : type,
      subType,
      cause,
      severity,
      road,
      direction: loc.direction ? prettyDir(loc.direction) : null,
      bearing: loc.bearing,
      location: loc.start,
      end: loc.end,
      title,
      description,
      lanesClosed: lanesClosed.length ? lanesClosed : (lanesRestricted > 0 ? Array.from({ length: lanesRestricted }, (_, i) => i + 1) : []),
      lanesTotal: original,
      speedLimit,
      delayMinutes,
      startTime: text(find(r, 'overallStartTime')),
      endTime: text(find(r, 'overallEndTime')),
      updated: text(r.situationRecordVersionTime) || text(find(r, 'situationRecordVersionTime')),
    });
  }
  return events;
}

const prettyDir = (d) => String(d).replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase().replace(/bound$/, 'bound');

function humanType(type, subType, full) {
  if (full) return 'Road closed';
  const s = subType ? String(subType).replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase() : '';
  const base = {
    accident: 'Accident', incident: 'Incident', roadworks: 'Roadworks', closure: 'Closure',
    lane_closure: 'Lane closure', speed_restriction: 'Speed restriction', congestion: 'Congestion', weather: 'Weather',
  }[type] || 'Incident';
  return s && !['accident', 'other', 'unknown'].includes(s) ? `${base} (${s})` : base;
}

// ---------- VMS & matrix signals ----------

/**
 * Parse VMS / signal *table* publications (static: where each sign is).
 * Returns Map<id, {location, bearing, road, direction, kind, lanes}>.
 */
export function parseVmsTable(doc) {
  const table = new Map();
  const recs = collect(doc, new Set(['vmsUnitRecord', 'vmsControllerRecord']));
  for (const { node } of recs) {
    const id = node['@_id'];
    if (!id) continue;
    const loc = extractLocation(node);
    const vmsType = text(find(node, 'vmsType', 'vmsTypeCode', 'vmsUsage')) || '';
    const count = asArray(node.vmsRecord).length || null;
    table.set(id, {
      location: loc.start,
      bearing: loc.bearing,
      road: loc.road,
      direction: loc.direction,
      kind: /matrix|lane|signal|amis|ms[1-4]/i.test(vmsType) ? 'signal' : 'vms',
      lanes: count,
      description: text(find(node, 'vmsDescription', 'description')) || null,
    });
  }
  return table;
}

const PICTO = {
  laneClosed: 'closed', laneclosed: 'closed', redCross: 'closed', closed: 'closed',
  laneDivertLeft: 'merge_left', keepLeft: 'merge_left', mergeLeft: 'merge_left', laneMergeLeft: 'merge_left',
  laneDivertRight: 'merge_right', keepRight: 'merge_right', mergeRight: 'merge_right', laneMergeRight: 'merge_right',
  endOfRestrictions: 'end', restrictionEnd: 'end', nationalSpeedLimit: 'end',
  maximumSpeedLimit: 'speed', speedLimit: 'speed', advisorySpeed: 'speed', advisorySpeedLimit: 'speed',
  blank: 'blank', blankVoid: 'blank',
};

function pictoAspect(p) {
  const d = text(find(p, 'pictogramDescription', 'pictogramCode', 'pictogramDescriptionCode')) || '';
  const key = d.replace(/\s+/g, '');
  let aspect = PICTO[key] || PICTO[key.charAt(0).toLowerCase() + key.slice(1)] || null;
  if (!aspect && /close|cross/i.test(d)) aspect = 'closed';
  if (!aspect && /speed/i.test(d)) aspect = 'speed';
  const sp = text(find(p, 'speedValue', 'speedAttribute', 'maximumSpeedLimit'));
  const speed = sp && Number(sp) > 0 ? kmhToPostedMph(sp) : null;
  if (!aspect && speed) aspect = 'speed';
  return { aspect: aspect || 'blank', speed, raw: d };
}

/**
 * Parse VMS / signal *status* publications against a table. Each sign
 * becomes one event of type 'signal' (has lane aspects) or 'vms' (text).
 */
export function parseVmsStatus(doc, table = new Map(), source = 'datex2') {
  const events = [];
  const units = collect(doc, new Set(['vmsUnit', 'vmsControllerStatus']));
  for (const { node: unit } of units) {
    const ref = unit.vmsUnitReference || unit.vmsControllerReference || unit.vmsUnitTableReference;
    const id = (ref && (ref['@_id'] || text(ref))) || unit['@_id'];
    const meta = table.get(id) || {};
    let location = meta.location;
    let brg = meta.bearing ?? null;
    let road = meta.road ?? null;
    let direction = meta.direction ?? null;
    if (!location) {
      const loc = extractLocation(unit);
      location = loc.start; brg = brg ?? loc.bearing; road = road ?? loc.road; direction = direction ?? loc.direction;
    }
    if (!location) continue;

    const lanes = [];
    const lines = [];
    let updated = null;
    const signs = asArray(unit.vms).length ? asArray(unit.vms) : asArray(unit.vmsStatus);
    signs.forEach((v, idx) => {
      const laneIdx = Number(v['@_vmsIndex'] ?? v['@_index'] ?? idx + 1);
      const pictos = findAll(v, 'vmsPictogram').concat(findAll(v, 'pictogram'));
      for (const p of pictos) {
        const a = pictoAspect(p);
        lanes.push({ lane: laneIdx, ...a });
      }
      for (const l of findAll(v, 'vmsTextLine')) {
        // v2 nests vmsTextLine three deep (indexed wrapper → object → string).
        let node = l;
        while (node && typeof node === 'object' && !('#text' in node)) node = Array.isArray(node) ? node[0] : node.vmsTextLine;
        const t = text(node);
        if (t && t.trim()) lines.push(t.trim());
      }
      updated = updated || text(find(v, 'timeLastSet', 'statusUpdateTime', 'lastSetTime'));
    });

    if (!lanes.length && !lines.length) continue; // blank sign — nothing to say

    // Lane-numbered gantry: DATEX vmsIndex runs left→right on most UK profiles;
    // we treat index 1 as lane 1 (nearside).
    const active = lanes.filter((l) => l.aspect !== 'blank');
    const speeds = active.filter((l) => l.speed).map((l) => l.speed);
    const closed = active.filter((l) => l.aspect === 'closed').map((l) => l.lane);
    const isSignal = meta.kind === 'signal' || (lanes.length > 0 && !lines.length);
    if (isSignal && !active.length) continue;

    const speedLimit = speeds.length ? Math.min(...speeds) : null;
    let severity = 'low';
    if (speedLimit && speedLimit <= 50) severity = 'medium';
    if (speedLimit && speedLimit <= 40) severity = 'high';
    if (closed.length) severity = closed.length >= 2 ? 'severe' : 'high';
    if (!isSignal && /accident|closed|queue|stop|incident|congestion/i.test(lines.join(' '))) severity = 'medium';

    events.push({
      id: `${source}:vms:${id}`,
      source,
      type: isSignal ? 'signal' : 'vms',
      severity,
      road: road || roadFromText(meta.description || ''),
      direction,
      bearing: brg,
      location,
      title: isSignal ? signalTitle(speedLimit, closed) : `Sign: ${lines.join(' / ')}`,
      description: lines.join(' / ') || null,
      lanesClosed: closed,
      speedLimit,
      signal: lanes.length ? { lanes: lanes.map(({ lane, aspect, speed }) => ({ lane, aspect, speed })), mandatory: closed.length > 0 } : null,
      vmsText: lines.length ? lines : null,
      updated,
    });
  }
  return events;
}

export function signalTitle(speed, closed) {
  const parts = [];
  if (closed.length) parts.push(`${closed.length > 1 ? 'Lanes' : 'Lane'} ${closed.join(' & ')} closed (red X)`);
  if (speed) parts.push(`${speed} mph`);
  return parts.length ? `Gantry: ${parts.join(', ')}` : 'Gantry signal set';
}

/** Parse any DATEX II document: returns {events, table}. */
export function parseDatex(xml, { source = 'datex2', table = new Map() } = {}) {
  const doc = typeof xml === 'string' ? parseXml(xml) : xml;
  const newTable = parseVmsTable(doc);
  for (const [k, v] of newTable) table.set(k, v);
  const events = [...parseSituations(doc, source), ...parseVmsStatus(doc, table, source)];
  return { events, table };
}
