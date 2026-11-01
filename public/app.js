import { RouteLine, METRES_PER_MILE, fmtMiles, haversine, mphToMs } from '/shared/geo.js';
import { gantryHtml, gantryIconHtml, vmsHtml, esc, describeSignal } from './signs.js';

const $ = (id) => document.getElementById(id);
const L = window.L;

// ---------------------------------------------------------------- state
const S = {
  from: null, to: null,           // {name, location}
  routes: [],                     // from /api/route
  route: null,                    // active route
  line: null,                     // RouteLine of active route
  along: 0,                       // metres travelled along active route
  driving: null,                  // 'gps' | 'sim' | null
  simTimer: null, gpsWatch: null, refreshTimer: null,
  alerted: new Map(),             // itemId -> Set(thresholds fired)
  currentAlert: null,
  offRouteCount: 0,
  lastFix: null,
};
const SEV = { low: 0, medium: 1, high: 2, severe: 3 };

// ---------------------------------------------------------------- map
const map = L.map('map', { zoomControl: false }).setView([53.0, -1.6], 7);
L.control.zoom({ position: 'bottomright' }).addTo(map);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '&copy; OpenStreetMap contributors',
}).addTo(map);
const layers = {
  events: L.layerGroup().addTo(map),
  routes: L.layerGroup().addTo(map),
  hazards: L.layerGroup().addTo(map),
  gantries: L.layerGroup().addTo(map),
  diversions: L.layerGroup().addTo(map),
  car: L.layerGroup().addTo(map),
};

const api = async (path, body) => {
  const res = await fetch(path, body ? { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) } : {});
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
};

function toast(msg, ms = 3500) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.add('hidden'), ms);
}

const ICON = { accident: '💥', incident: '⚠️', roadworks: '🚧', closure: '⛔', lane_closure: '🚧', speed_restriction: '⏱', congestion: '🚗', weather: '🌧', vms: '💬', signal: '▦' };
const sevClass = (s) => `sev-${s || 'low'}`;

// ---------------------------------------------------------------- status / sources
async function loadStatus() {
  try {
    const st = await api('/api/status');
    $('demo-badge').classList.toggle('hidden', !st.demo);
    $('sources').innerHTML = 'Data: ' + st.providers.map((p) =>
      `<span class="${p.lastError ? 'src-err' : ''}" title="${esc(p.lastError || `updated ${p.lastOk || 'never'}`)}">${esc(p.label)} (${p.count})</span>`).join(' · ') +
      ' · Routing © OSRM · Map © OpenStreetMap';
  } catch { /* ignore */ }
}

// ---------------------------------------------------------------- background events (whole map)
let evTimer = null;
async function loadEvents() {
  if (map.getZoom() < 8) { layers.events.clearLayers(); return; }
  const b = map.getBounds();
  try {
    const evs = await api(`/api/events?bbox=${[b.getWest(), b.getSouth(), b.getEast(), b.getNorth()].map((n) => n.toFixed(4)).join(',')}`);
    layers.events.clearLayers();
    for (const e of evs) {
      if (e.type === 'signal') {
        if (map.getZoom() < 11) continue;
        L.marker([e.location[1], e.location[0]], { icon: L.divIcon({ className: 'gantry-icon', html: gantryIconHtml(e), iconSize: null }) })
          .bindPopup(eventPopup(e)).addTo(layers.events);
        continue;
      }
      L.marker([e.location[1], e.location[0]], { icon: L.divIcon({ className: `ev-icon ${sevClass(e.severity)}`, html: ICON[e.type] || '⚠️', iconSize: [26, 26] }) })
        .bindPopup(eventPopup(e)).addTo(layers.events);
    }
  } catch { /* ignore */ }
}
map.on('moveend', () => { clearTimeout(evTimer); evTimer = setTimeout(loadEvents, 400); });

function eventPopup(e) {
  return `<div class="popup">
    <b>${esc(e.title)}</b>${e.source === 'demo' ? ' <span class="badge badge-demo">DEMO</span>' : ''}
    ${e.signal ? gantryHtml(e.signal) : ''}${e.vmsText ? vmsHtml(e.vmsText) : ''}
    ${e.description ? `<p>${esc(e.description)}</p>` : ''}
    <small>${esc(e.source)}${e.direction ? ' · ' + esc(e.direction) : ''}${e.updated ? ' · updated ' + new Date(e.updated).toLocaleTimeString('en-GB', { timeStyle: 'short' }) : ''}</small>
  </div>`;
}

// ---------------------------------------------------------------- geocoding inputs
function setupSearch(inputId, listId, key) {
  const input = $(inputId), list = $(listId);
  let t = null;
  input.addEventListener('input', () => {
    S[key] = null;
    clearTimeout(t);
    const q = input.value.trim();
    if (q.length < 3) { list.innerHTML = ''; return; }
    t = setTimeout(async () => {
      try {
        const res = await api(`/api/geocode?q=${encodeURIComponent(q)}`);
        list.innerHTML = res.map((r, i) => `<li data-i="${i}">${esc(r.name)}</li>`).join('');
        list.onclick = (ev) => {
          const li = ev.target.closest('li');
          if (!li) return;
          const r = res[Number(li.dataset.i)];
          S[key] = r;
          input.value = r.name.split(',').slice(0, 3).join(',');
          list.innerHTML = '';
        };
      } catch (e) { list.innerHTML = `<li class="muted">${esc(e.message)}</li>`; }
    }, 350);
  });
  input.addEventListener('blur', () => setTimeout(() => (list.innerHTML = ''), 250));
}
setupSearch('from', 'from-suggest', 'from');
setupSearch('to', 'to-suggest', 'to');

$('use-gps').onclick = () => {
  if (!navigator.geolocation) return toast('Location not available in this browser');
  navigator.geolocation.getCurrentPosition(
    (p) => { S.from = { name: 'My location', location: [p.coords.longitude, p.coords.latitude] }; $('from').value = 'My location'; },
    (e) => toast('Could not get location: ' + e.message),
    { enableHighAccuracy: true, timeout: 10000 });
};

async function resolve(key, inputId) {
  if (S[key]) return S[key];
  const q = $(inputId).value.trim();
  if (!q) throw new Error(`Enter a ${key === 'from' ? 'start' : 'destination'}`);
  const res = await api(`/api/geocode?q=${encodeURIComponent(q)}`);
  if (!res.length) throw new Error(`Couldn't find "${q}"`);
  return (S[key] = res[0]);
}

$('route-form').onsubmit = async (ev) => {
  ev.preventDefault();
  const btn = $('go');
  try {
    btn.disabled = true; btn.textContent = 'Checking the road ahead…';
    $('from-suggest').innerHTML = $('to-suggest').innerHTML = '';
    const [from, to] = [await resolve('from', 'from'), await resolve('to', 'to')];
    const { routes } = await api('/api/route', { from: from.location, to: to.location });
    stopDrive();
    S.routes = routes;
    renderRouteChoices();
    selectRoute(routes[0], { fit: true });
    loadStatus();
  } catch (e) { toast(e.message, 5000); } finally { btn.disabled = false; btn.textContent = 'Plan route'; }
};

// ---------------------------------------------------------------- routes
const mins = (m) => (m >= 60 ? `${Math.floor(m / 60)} h ${Math.round(m % 60)} min` : `${Math.round(m)} min`);

function renderRouteChoices() {
  $('routes').innerHTML = S.routes.map((r, i) => {
    const a = r.analysis;
    const serious = a.items.filter((x) => x.kind !== 'vms' && SEV[x.severity] >= 2).length;
    return `<button class="route-card ${S.route?.id === r.id ? 'active' : ''}" data-i="${i}">
      <div class="rc-top"><b>${mins(r.etaMinutes)}</b> <span>${fmtMiles(r.distance)}</span> <span class="tag ${sevClass(a.worstSeverity)}">${a.totalDelayMinutes ? `+${a.totalDelayMinutes} min delays` : 'clear'}</span></div>
      <div class="rc-sub">${esc(r.label || '')} · via ${esc(r.summary || 'local roads')}${serious ? ` · <b>${serious} serious</b>` : ''}</div>
    </button>`;
  }).join('');
  $('routes').onclick = (ev) => {
    const c = ev.target.closest('.route-card');
    if (c) selectRoute(S.routes[Number(c.dataset.i)], { fit: true });
  };
}

function selectRoute(route, { fit = false, keepAlong = false } = {}) {
  S.route = route;
  S.line = new RouteLine(route.coords);
  if (!keepAlong) S.along = 0;
  S.alerted = new Map();
  layers.routes.clearLayers();
  for (const r of S.routes) {
    if (r.id === route.id) continue;
    L.polyline(r.coords.map(([lo, la]) => [la, lo]), { color: '#7d8fa3', weight: 5, opacity: 0.6 })
      .on('click', () => selectRoute(r)).addTo(layers.routes);
  }
  L.polyline(route.coords.map(([lo, la]) => [la, lo]), { color: '#0b5394', weight: 8, opacity: 0.25 }).addTo(layers.routes);
  L.polyline(route.coords.map(([lo, la]) => [la, lo]), { color: '#1e88e5', weight: 5 }).addTo(layers.routes);
  drawHazards();
  if (fit) map.fitBounds(L.latLngBounds(route.coords.map(([lo, la]) => [la, lo])), { padding: [40, 40] });
  renderRouteChoices();
  $('drive-controls').classList.remove('hidden');
  $('ahead').classList.remove('hidden');
  renderAhead();
}

function drawHazards() {
  layers.hazards.clearLayers();
  layers.gantries.clearLayers();
  const a = S.route.analysis;
  for (const it of a.items) {
    if (it.kind === 'zone' || (it.end - it.start > 200)) {
      const seg = S.line.slice(it.start, Math.max(it.end, it.start + 100));
      L.polyline(seg.map(([lo, la]) => [la, lo]), { className: `hz-line ${sevClass(it.severity)}`, weight: 7 }).addTo(layers.hazards);
    }
    if (it.kind === 'vms') continue;
    L.marker([it.location[1], it.location[0]], { icon: L.divIcon({ className: `ev-icon big ${sevClass(it.severity)}`, html: ICON[it.type] || '⚠️', iconSize: [32, 32] }) })
      .bindPopup(`<b>${esc(it.headline)}</b><br>${it.details.map(esc).join('<br>')}`).addTo(layers.hazards);
  }
  for (const g of a.gantries) {
    L.marker([g.location[1], g.location[0]], { icon: L.divIcon({ className: 'gantry-icon', html: gantryIconHtml(g), iconSize: null }), zIndexOffset: 500 })
      .bindPopup(`<b>Gantry · ${esc(g.road || '')}</b> ${g.source === 'demo' ? '<span class="badge badge-demo">DEMO</span>' : ''}${gantryHtml(g.signal)}<small>${fmtMiles(g.along)} from start</small>`)
      .addTo(layers.gantries);
  }
}

// ---------------------------------------------------------------- road-ahead list
function visibleItems() {
  const minor = $('minor').checked;
  return S.route.analysis.items.filter((it) => it.end >= S.along - 50 && (minor || it.kind === 'vms' || SEV[it.severity] >= 1));
}

function exitLine(it) {
  const ex = (it.exitsBefore || []).find((x) => x.along > S.along + 100);
  if (!ex) return S.along > 0 && it.exitBefore ? '<span class="noexit">No exits left before this</span>' : (it.exitBefore === null && it.road && /^M|\(M\)/.test(it.road) ? '<span class="noexit">No junction before this on your route</span>' : '');
  const label = ex.ref ? `${ex.ref}${ex.name ? ' ' + esc(ex.name) : ''}` : 'Exit';
  return `<span class="exit">Last exit before it: <b>${esc(label)}</b> in ${fmtMiles(ex.along - S.along)}</span>`;
}

function renderAhead() {
  if (!S.route) return;
  const items = visibleItems();
  const a = S.route.analysis;
  $('ahead-meta').textContent = `· ${items.filter((i) => i.kind !== 'vms').length} item(s) · ~${a.totalDelayMinutes} min delay`;
  if (!items.length) { $('ahead-list').innerHTML = '<li class="clear">✓ Nothing reported on your route ahead</li>'; return; }
  $('ahead-list').innerHTML = items.map((it) => {
    const d = it.start - S.along;
    const inside = d <= 0;
    const dist = inside ? 'NOW' : fmtMiles(d);
    const signs = it.kind === 'zone'
      ? pickSigns(it.gantries).map((g) => `<div class="sign-at"><small>${g.along - S.along > 0 ? fmtMiles(g.along - S.along) : 'passed'}</small>${gantryHtml(g.signal, { compact: true })}</div>`).join('')
      : '';
    const vms = it.events.filter((e) => e.vmsText).map((e) => vmsHtml(e.vmsText)).join('');
    const demo = (it.events.some((e) => e.source === 'demo') || it.gantries.some((g) => g.source === 'demo')) ? '<span class="badge badge-demo">DEMO</span>' : '';
    return `<li class="ahead-item ${sevClass(it.severity)} kind-${it.kind}" data-id="${esc(it.id)}">
      <div class="ai-dist">${dist}<small>${it.kind === 'zone' ? `for ${fmtMiles(it.end - it.start)}` : ''}</small></div>
      <div class="ai-main">
        <div class="ai-title">${ICON[it.type] || ''} ${esc(it.headline)} ${demo}</div>
        ${it.cause ? `<div class="ai-cause">Cause: ${esc(it.cause)}</div>` : ''}
        <ul class="ai-details">${it.details.slice(0, 4).map((x) => `<li>${esc(x)}</li>`).join('')}</ul>
        ${signs ? `<div class="sign-strip">${signs}</div>` : ''}${vms}
        <div class="ai-foot">${it.delayMinutes ? `<span class="delay">+${Math.round(it.delayMinutes)} min${it.delayIsEstimate ? ' (est.)' : ''}</span>` : ''} ${exitLine(it)}
          ${it.kind !== 'vms' && !inside ? `<button class="btn btn-small" data-divert="${esc(it.id)}">Avoid this</button>` : ''}</div>
      </div>
    </li>`;
  }).join('');
}

/** Up to 4 representative gantries for a zone: first, first merge, first red X, worst. */
function pickSigns(gs) {
  if (gs.length <= 4) return gs;
  const pick = new Set([gs[0]]);
  const merge = gs.find((g) => g.signal?.lanes?.some((l) => /merge/.test(l.aspect)));
  const closed = gs.find((g) => g.lanesClosed?.length);
  if (merge) pick.add(merge);
  if (closed) pick.add(closed);
  pick.add(gs.reduce((a, b) => ((b.speedLimit || 99) < (a.speedLimit || 99) ? b : a)));
  return [...pick].sort((a, b) => a.along - b.along).slice(0, 4);
}

$('ahead-list').addEventListener('click', (ev) => {
  const b = ev.target.closest('[data-divert]');
  if (b) return findDiversion(b.dataset.divert);
  const li = ev.target.closest('.ahead-item');
  if (li) {
    const it = S.route.analysis.items.find((x) => x.id === li.dataset.id);
    if (it) map.setView([it.location[1], it.location[0]], 13);
  }
});
$('minor').onchange = renderAhead;

// ---------------------------------------------------------------- driving
function setCar(along) {
  const p = S.line.pointAt(along);
  layers.car.clearLayers();
  const brg = S.line.bearingAt(along);
  L.marker([p[1], p[0]], { icon: L.divIcon({ className: 'car', html: `<div style="transform:rotate(${brg - 90}deg)">➤</div>`, iconSize: [30, 30] }), zIndexOffset: 1000 }).addTo(layers.car);
  return p;
}

function currentLimit() {
  // The setting of the last gantry you passed applies until the next one.
  const passed = S.route.analysis.gantries.filter((g) => g.along <= S.along + 30);
  const last = passed[passed.length - 1];
  if (!last || S.along - last.along > 2500) return null;
  if (!last.signal?.lanes?.some((l) => l.aspect !== 'blank' && l.aspect !== 'end')) return null;
  return last.speedLimit;
}

function hudUpdate() {
  const lim = currentLimit();
  $('hud-limit').textContent = lim || '–';
  $('hud-limit').parentElement.classList.toggle('active', !!lim);
  const remain = S.route.distance - S.along;
  const delays = S.route.analysis.items.filter((i) => i.kind !== 'vms' && i.end > S.along).reduce((s, i) => s + (i.delayMinutes || 0), 0);
  const eta = (remain / S.route.distance) * (S.route.duration / 60) + delays;
  $('hud-eta').textContent = `${fmtMiles(remain)} · ${mins(eta)} · arrive ${new Date(Date.now() + eta * 60000).toLocaleTimeString('en-GB', { timeStyle: 'short' })}`;
  const step = S.route.steps.find((s) => S.along >= s.from && S.along <= s.to);
  $('hud-road').textContent = step ? [step.ref, step.name].filter(Boolean).join(' · ') || '—' : '—';
}

function tick() {
  if (!S.route) return;
  const p = setCar(S.along);
  if (S.driving) map.panTo([p[1], p[0]], { animate: true });
  hudUpdate();
  checkAlerts();
  renderAhead();
  if (S.along >= S.route.distance - 20) { toast('You have arrived'); stopDrive(); }
}

function startDrive(mode) {
  stopDrive();
  if (!S.route) return;
  S.driving = mode;
  $('hud').classList.remove('hidden');
  document.body.classList.add('driving');
  map.setZoom(13);
  if (mode === 'sim') {
    let last = performance.now();
    S.simTimer = setInterval(() => {
      const now = performance.now();
      const dt = (now - last) / 1000; last = now;
      const lim = currentLimit() || 70;
      const step = S.route.steps.find((s) => S.along >= s.from && S.along <= s.to);
      const roadSpeed = step && /^M|\(M\)/.test(step.ref || '') ? 70 : 45;
      S.along = Math.min(S.route.distance, S.along + mphToMs(Math.min(lim, roadSpeed)) * dt * Number($('sim-speed').value));
      tick();
    }, 500);
  } else {
    if (!navigator.geolocation) { toast('GPS not available'); return stopDrive(); }
    S.gpsWatch = navigator.geolocation.watchPosition(onFix, (e) => toast('GPS: ' + e.message), { enableHighAccuracy: true, maximumAge: 2000 });
    requestWakeLock();
  }
  // Pull fresh data every minute — gantry settings change constantly.
  S.refreshTimer = setInterval(refreshAnalysis, 60000);
  primeSpeech();
  tick();
}

function onFix(pos) {
  const p = [pos.coords.longitude, pos.coords.latitude];
  const heading = Number.isFinite(pos.coords.heading) && pos.coords.speed > 3 ? pos.coords.heading : null;
  const hit = S.line.snap(p, { maxDist: 120, bearing: heading, maxBearingDiff: 70, minAlong: S.along - 300 })
    || S.line.snap(p, { maxDist: 120, minAlong: S.along - 300 });
  S.lastFix = p;
  if (!hit) {
    if (++S.offRouteCount >= 4) { S.offRouteCount = 0; replanFrom(p); }
    return;
  }
  S.offRouteCount = 0;
  S.along = Math.max(S.along - 50, hit.along);
  tick();
}

async function replanFrom(p) {
  toast('Off route — re-planning…');
  try {
    const { routes } = await api('/api/route', { from: p, to: S.to?.location || S.route.coords[S.route.coords.length - 1] });
    S.routes = routes;
    selectRoute(routes[0]);
  } catch (e) { toast(e.message); }
}

let wakeLock = null;
async function requestWakeLock() { try { wakeLock = await navigator.wakeLock?.request('screen'); } catch { /* optional */ } }

function stopDrive() {
  clearInterval(S.simTimer); clearInterval(S.refreshTimer);
  if (S.gpsWatch != null) navigator.geolocation.clearWatch(S.gpsWatch);
  S.simTimer = S.refreshTimer = S.gpsWatch = null;
  S.driving = null;
  $('hud').classList.add('hidden');
  document.body.classList.remove('driving');
  hideAlert();
  wakeLock?.release?.(); wakeLock = null;
}

$('drive-gps').onclick = () => startDrive('gps');
$('drive-sim').onclick = () => startDrive('sim');
$('hud-stop').onclick = stopDrive;

async function refreshAnalysis() {
  if (!S.route) return;
  try {
    const r = await api('/api/analyse', { routeId: S.route.id });
    S.route.analysis = r.analysis;
    S.route.etaMinutes = r.etaMinutes;
    drawHazards();
    renderAhead();
  } catch (e) { if (/expired/.test(e.message)) toast(e.message); }
}

// ---------------------------------------------------------------- look-ahead alerts
function thresholds() {
  const h = Number($('horizon').value) * METRES_PER_MILE;
  return [h, 2 * METRES_PER_MILE, 0.5 * METRES_PER_MILE].filter((x, i, arr) => i === 0 || x < arr[0]);
}

function checkAlerts() {
  const th = thresholds();
  for (const it of S.route.analysis.items) {
    if (it.kind === 'vms' || SEV[it.severity] < 1) continue;
    const d = it.start - S.along;
    if (d < -50) continue;
    const fired = S.alerted.get(it.id) || new Set();
    // Fire the largest threshold we're inside that hasn't fired yet.
    const t = th.find((x) => d <= x && !fired.has(x));
    if (t == null) continue;
    th.filter((x) => x >= t).forEach((x) => fired.add(x));
    S.alerted.set(it.id, fired);
    showAlert(it, d);
    break;
  }
  if (S.currentAlert) {
    const it = S.route.analysis.items.find((x) => x.id === S.currentAlert);
    if (!it || it.end < S.along) hideAlert();
    else {
      $('alert-dist').innerHTML = it.start - S.along > 0 ? `${fmtMiles(it.start - S.along)}<small>ahead</small>` : 'NOW';
      $('alert-sub').innerHTML = alertSub(it);
      $('alert-divert').classList.toggle('hidden', it.start - S.along <= 0);
    }
  }
}

function showAlert(it, d) {
  S.currentAlert = it.id;
  $('alert').className = `alert ${sevClass(it.severity)}`;
  $('alert-dist').innerHTML = d > 0 ? `${fmtMiles(d)}<small>ahead</small>` : 'NOW';
  $('alert-title').textContent = it.headline;
  const ex = (it.exitsBefore || []).find((x) => x.along > S.along + 100);
  $('alert-sub').innerHTML = alertSub(it);
  // Show what's coming: the first red-X gantry if there is one, otherwise the lowest limit.
  const gs = (it.gantries || []).filter((x) => x.along > S.along - 50);
  const g = gs.find((x) => x.lanesClosed?.length) || gs.reduce((a, b) => (!a || (b.speedLimit || 99) < (a.speedLimit || 99) ? b : a), null);
  $('alert-sign').innerHTML = g ? `${gantryHtml(g.signal, { compact: true })}<small class="sign-dist">in ${fmtMiles(Math.max(0, g.along - S.along))}</small>` : '';
  $('alert-divert').classList.toggle('hidden', d <= 0);
  speak(spoken(it, d, ex));
  if (navigator.vibrate) navigator.vibrate([200, 100, 200]);
}

function alertSub(it) {
  return [
    it.cause ? esc(it.cause) : null,
    it.delayMinutes ? `+${Math.round(it.delayMinutes)} min` : null,
    exitLine(it),
  ].filter(Boolean).join(' · ');
}

function hideAlert() { S.currentAlert = null; $('alert').classList.add('hidden'); }
$('alert-dismiss').onclick = hideAlert;
$('alert-divert').onclick = () => S.currentAlert && findDiversion(S.currentAlert);

function sayMiles(m) {
  const mi = m / METRES_PER_MILE;
  if (mi < 0.4) return `${Math.round(m / 100) * 100} metres`;
  if (mi < 0.75) return 'half a mile';
  if (mi < 1.25) return 'one mile';
  return `${Math.round(mi)} miles`;
}

function spoken(it, d, ex) {
  const parts = [];
  const road = it.road ? `on the ${it.road.split('').join(' ')}` : '';
  if (it.kind === 'zone') {
    if (it.lanesClosed?.length) {
      const where = it.closureAt != null ? sayMiles(it.closureAt - S.along) : sayMiles(d);
      parts.push(`Heads up. ${it.lanesClosed.length > 1 ? 'Lanes' : 'Lane'} ${it.lanesClosed.join(' and ')} closed in ${where} ${road}.`);
      if (it.speedLimit) parts.push(`Speed limit drops to ${it.speedLimit} from ${sayMiles(d)}.`);
    } else parts.push(`Heads up. ${it.speedLimit} mile an hour limit in ${sayMiles(d)} ${road}.`);
  } else {
    parts.push(`Heads up. ${it.headline.split('·')[0].trim()} in ${sayMiles(d)} ${road}.`);
  }
  if (it.delayMinutes >= 3) parts.push(`About ${Math.round(it.delayMinutes)} minutes delay.`);
  if (ex) parts.push(`Last exit before it${ex.ref ? `, junction ${ex.ref.replace(/^J/, '')},` : ''} is in ${sayMiles(ex.along - S.along)}.`);
  return parts.join(' ');
}

let voice = null;
function primeSpeech() {
  if (!window.speechSynthesis) return;
  const pick = () => { voice = speechSynthesis.getVoices().find((v) => v.lang === 'en-GB') || null; };
  pick();
  speechSynthesis.onvoiceschanged = pick;
}
function speak(text) {
  if (!$('voice').checked || !window.speechSynthesis) return;
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  if (voice) u.voice = voice;
  u.lang = 'en-GB';
  u.rate = 1.02;
  speechSynthesis.speak(u);
}

// ---------------------------------------------------------------- diversions
async function findDiversion(hazardId) {
  const box = $('diversions');
  box.classList.remove('hidden');
  box.innerHTML = '<h2>Finding a way round…</h2><p class="muted">Checking alternative routes against live signals and incidents.</p>';
  layers.diversions.clearLayers();
  const pos = S.along > 0 ? S.line.pointAt(S.along) : S.route.coords[0];
  try {
    const res = await api('/api/divert', { routeId: S.route.id, hazardId, position: pos, to: S.to?.location });
    if (!res.options.length) {
      box.innerHTML = `<h2>No sensible diversion</h2><p>Every route round <b>${esc(res.hazard.headline)}</b> is slower or hits the same problem. Staying on route: ~${mins(res.stay.minutes)}.</p><button class="btn" id="div-close">OK</button>`;
    } else {
      box.innerHTML = `<h2>Avoid: ${esc(res.hazard.headline)}</h2>
        <div class="div-opt stay">Stay on route · <b>${mins(res.stay.minutes)}</b> (incl. ~${res.stay.delayMinutes} min delays)</div>
        ${res.options.map((o, i) => `<button class="div-opt" data-i="${i}">
          <b>${mins(o.etaMinutes)}</b> <span class="${o.deltaMinutes <= 0 ? 'good' : 'bad'}">${o.deltaMinutes <= 0 ? `${-o.deltaMinutes} min faster` : `+${o.deltaMinutes} min`}</span>
          <div class="muted">${esc(o.label)} · via ${esc(o.summary || 'local roads')} · ${o.analysis.items.filter((x) => x.kind !== 'vms').length} other item(s)</div></button>`).join('')}
        <button class="btn btn-ghost" id="div-close">Cancel</button>`;
      box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      res.options.forEach((o, i) => L.polyline(o.coords.map(([lo, la]) => [la, lo]), { color: i === 0 ? '#2e7d32' : '#66bb6a', weight: 5, dashArray: '8 6' }).addTo(layers.diversions));
      box.querySelectorAll('.div-opt[data-i]').forEach((b) => (b.onclick = () => {
        const o = res.options[Number(b.dataset.i)];
        layers.diversions.clearLayers();
        box.classList.add('hidden');
        const wasDriving = S.driving;
        S.routes = [o];
        selectRoute(o);
        hideAlert();
        toast('Diversion selected');
        if (wasDriving) { S.driving = wasDriving; tick(); }
      }));
    }
    $('div-close').onclick = () => { box.classList.add('hidden'); layers.diversions.clearLayers(); };
  } catch (e) {
    box.innerHTML = `<h2>Diversion failed</h2><p>${esc(e.message)}</p>`;
  }
}

// ---------------------------------------------------------------- misc
$('panel-toggle').onclick = () => $('panel').classList.toggle('collapsed');
loadStatus();
setInterval(loadStatus, 60000);
loadEvents();

// Expose for debugging in the console.
window.__roadAhead = S;
