// Transport for London road disruptions (TIMS). Public API, key optional.
// https://api.tfl.gov.uk/Road/all/Disruption
import { lanesFromText, roadFromText } from '../lib/events.js';
import { directionToBearing } from '../../shared/geo.js';

const SEV = { 'No impact': 'low', Minimal: 'low', Moderate: 'medium', Serious: 'high', Severe: 'severe' };
const CAT = { Works: 'roadworks', Collisions: 'accident', 'Asset issues': 'incident', 'Planned events': 'incident', Incidents: 'incident', 'Traffic incidents': 'incident', 'Traffic volume': 'congestion', Weather: 'weather' };

export function parseTfl(list) {
  const out = [];
  for (const d of list || []) {
    let loc = null;
    try { loc = JSON.parse(d.point); } catch { /* ignore */ }
    if (!Array.isArray(loc) || loc.length < 2) continue;
    const text = `${d.comments || ''} ${d.currentUpdate || ''}`;
    const lanes = lanesFromText(text);
    const dir = (d.comments || '').match(/\((north|south|east|west)bound\)/i)?.[1];
    const closed = d.hasClosures || lanes.fullClosure;
    out.push({
      id: `tfl:${d.id}`,
      source: 'tfl',
      type: closed ? 'closure' : lanes.lanesClosed.length ? (CAT[d.category] === 'roadworks' ? 'roadworks' : 'lane_closure') : CAT[d.category] || 'incident',
      subType: d.subCategory || null,
      severity: closed ? 'severe' : SEV[d.severity] || 'medium',
      road: roadFromText(d.location || d.comments),
      direction: dir ? `${dir.toLowerCase()}bound` : null,
      bearing: dir ? directionToBearing(dir) : null,
      location: [Number(loc[0]), Number(loc[1])],
      title: `${d.subCategory || d.category}${d.location ? ' · ' + d.location : ''}`,
      description: [d.comments, d.currentUpdate].filter(Boolean).join(' — ').trim(),
      lanesClosed: lanes.lanesClosed,
      lanesTotal: lanes.lanesTotal,
      startTime: d.startDateTime,
      endTime: d.endDateTime,
      updated: d.lastModifiedTime,
    });
  }
  return out;
}

export function tflProvider({ appKey, intervalMs = 120000, fetchImpl = fetch } = {}) {
  const url = `https://api.tfl.gov.uk/Road/all/Disruption${appKey ? `?app_key=${encodeURIComponent(appKey)}` : ''}`;
  return {
    name: 'tfl',
    label: 'Transport for London',
    intervalMs,
    async poll() {
      const res = await fetchImpl(url, { headers: { 'user-agent': 'uk-traffic-maps' } });
      if (!res.ok) throw new Error(`TfL HTTP ${res.status}`);
      return parseTfl(await res.json());
    },
  };
}
