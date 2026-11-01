// Renders smart-motorway gantries and message signs as HTML that looks like
// the real thing: one aspect per lane, lane 1 (nearside) on the left.

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

function aspectHtml(l) {
  switch (l.aspect) {
    case 'speed':
      return `<div class="asp asp-speed" title="Lane ${l.lane}: ${l.speed} mph"><span>${l.speed}</span></div>`;
    case 'closed':
      return `<div class="asp asp-closed" title="Lane ${l.lane}: closed — red X (mandatory)"><span>✕</span></div>`;
    case 'merge_left':
      return `<div class="asp asp-arrow" title="Lane ${l.lane}: move left"><span>↙</span></div>`;
    case 'merge_right':
      return `<div class="asp asp-arrow" title="Lane ${l.lane}: move right"><span>↘</span></div>`;
    case 'end':
      return `<div class="asp asp-end" title="Lane ${l.lane}: end of restrictions"><span></span></div>`;
    default:
      return `<div class="asp asp-blank" title="Lane ${l.lane}: blank"></div>`;
  }
}

export function gantryHtml(signal, { compact = false } = {}) {
  if (!signal?.lanes?.length) return '';
  const lanes = [...signal.lanes].sort((a, b) => a.lane - b.lane);
  return `<div class="gantry ${compact ? 'gantry-compact' : ''}" role="img" aria-label="${esc(describeSignal(signal))}">${lanes.map(aspectHtml).join('')}</div>`;
}

export function vmsHtml(lines) {
  if (!lines?.length) return '';
  return `<div class="vms">${lines.map((l) => `<div>${esc(l)}</div>`).join('')}</div>`;
}

export function describeSignal(signal) {
  const lanes = [...(signal?.lanes || [])].sort((a, b) => a.lane - b.lane);
  return lanes.map((l) => `lane ${l.lane} ${l.aspect === 'speed' ? l.speed + ' mph' : l.aspect.replace('_', ' ')}`).join(', ');
}

/** Small map icon: compact gantry for restricted, grey tick for blank. */
export function gantryIconHtml(g) {
  const restricted = g.speedLimit || g.lanesClosed?.length || g.signal?.lanes?.some((l) => l.aspect !== 'blank');
  if (!restricted) return '<div class="gantry-dot"></div>';
  return gantryHtml(g.signal, { compact: true });
}

export { esc };
