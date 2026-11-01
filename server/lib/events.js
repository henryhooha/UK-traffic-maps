// The normalised event model every provider maps into.
//
// {
//   id:          string, unique across providers ("<source>:<native id>")
//   source:      'national-highways' | 'traffic-scotland' | 'tfl' | 'demo' | ...
//   type:        'accident' | 'incident' | 'roadworks' | 'closure' | 'lane_closure'
//                | 'speed_restriction' | 'congestion' | 'weather' | 'signal' | 'vms'
//   severity:    'low' | 'medium' | 'high' | 'severe'
//   road:        'M1' | 'A1(M)' | ... (may be null)
//   direction:   free text from the feed, e.g. 'northbound' (may be null)
//   bearing:     travel bearing the event applies to, degrees (null = unknown)
//   location:    [lon, lat]
//   end:         [lon, lat] for linear events (optional)
//   title:       short headline
//   description: longer text
//   lanesClosed: [1, 2]  lane 1 = nearside (left) lane; 0 = hard shoulder
//   lanesTotal:  number (optional)
//   speedLimit:  mph (optional)
//   delayMinutes number (optional)
//   signal:      for smart-motorway gantries: { lanes: [{ lane, aspect, speed }], mandatory }
//                aspect: 'speed' | 'closed' (red X) | 'merge_left' | 'merge_right' | 'end' | 'blank'
//   vmsText:     ['QUEUE', 'AHEAD'] for message signs
//   startTime, endTime, updated: ISO strings
// }

export const SEVERITY_RANK = { low: 0, medium: 1, high: 2, severe: 3 };

export const maxSeverity = (...s) =>
  s.filter(Boolean).reduce((a, b) => (SEVERITY_RANK[b] > SEVERITY_RANK[a] ? b : a), 'low');

/** Round km/h (DATEX II units) to the posted UK mph value. */
export function kmhToPostedMph(kmh) {
  const mph = Number(kmh) / 1.609344;
  return Math.round(mph / 10) * 10;
}

const WORD_NUM = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6 };

/** Pull lane closures out of free text ("Lanes one and two (of three) are closed"). */
export function lanesFromText(text = '') {
  const t = text.toLowerCase();
  const out = new Set();
  let total = null;
  const of = t.match(/\(of (one|two|three|four|five|six|\d)\)/);
  if (of) total = WORD_NUM[of[1]] ?? Number(of[1]);
  const m = t.match(/\blanes?\s+((?:(?:one|two|three|four|five|six|\d)(?:\s*,\s*|\s+and\s+|\s*&\s*|\s+to\s+)?)+)\s*(?:\(of [^)]*\)\s*)?(?:is|are|have been|will be)?\s*closed/);
  if (m) {
    const nums = m[1].match(/one|two|three|four|five|six|\d/g).map((w) => WORD_NUM[w] ?? Number(w));
    if (/\bto\b/.test(m[1]) && nums.length === 2) for (let i = nums[0]; i <= nums[1]; i++) out.add(i);
    else nums.forEach((n) => out.add(n));
  }
  if (/hard shoulder (is )?closed/.test(t)) out.add(0);
  const all = /all lanes (are )?closed|road (is )?closed|carriageway (is )?closed/.test(t);
  return { lanesClosed: [...out].sort((a, b) => a - b), lanesTotal: total, fullClosure: all };
}

/** Pull a road number out of text: "M6", "A1(M)", "A14". */
export function roadFromText(text = '') {
  const m = String(text).match(/\b([MA]\d{1,4}(?:\([M]\))?)\b/);
  return m ? m[1].toUpperCase() : null;
}

/** Normalise a road reference so "M 1", "m1", "M1 J12" compare equal. */
export function normRoad(r) {
  if (!r) return null;
  const m = String(r).toUpperCase().replace(/\s+/g, '').match(/^([MAB]\d{1,4}(?:\(M\))?)/);
  return m ? m[1] : null;
}
