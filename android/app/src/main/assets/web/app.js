/* Road Ahead — phone UI. All logic lives in the native app (window.Android);
   this page only draws state pushed via window.RA.* and forwards taps. */
(function () {
  'use strict';
  const A = window.Android;
  const $ = (id) => document.getElementById(id);
  const { gantryHtml, vmsHtml, gantryIconHtml, esc } = window.Signs;
  const MI = 1609.344;
  const SEV = { low: 0, medium: 1, high: 2, severe: 3 };

  const S = { routes: [], analysis: {}, state: {}, activeId: null, follow: true, sheet: null, cbs: {}, cbSeq: 0, lastAlertKey: null, simSpeed: 10 };

  // ------------------------------------------------------------ helpers
  const fmtMi = (m) => { const mi = m / MI; return mi < 0.1 ? `${Math.max(0, Math.round(m / 10) * 10)} m` : mi < 10 ? `${mi.toFixed(1)} mi` : `${Math.round(mi)} mi`; };
  const mins = (m) => (m >= 60 ? `${Math.floor(m / 60)} h ${Math.round(m % 60)} min` : `${Math.round(m)} min`);
  const ll = ([lo, la]) => [la, lo];
  function toast(msg, ms = 3000) { const t = $('toast'); t.textContent = msg; t.classList.remove('hidden'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.add('hidden'), ms); }
  function call(fn, ...args) { try { return A && A[fn] ? A[fn](...args) : undefined; } catch (e) { console.error(fn, e); } }
  function withCb(fn, ...args) { return new Promise((res) => { const id = 'c' + (++S.cbSeq); S.cbs[id] = res; call(fn, ...args, id); }); }

  function cumDist(coords) {
    const cum = [0];
    for (let i = 1; i < coords.length; i++) {
      const [a, b] = [coords[i - 1], coords[i]];
      const dLat = (b[1] - a[1]) * Math.PI / 180, dLon = (b[0] - a[0]) * Math.PI / 180;
      const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * Math.PI / 180) * Math.cos(b[1] * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
      cum.push(cum[i - 1] + 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h))));
    }
    return cum;
  }
  function slice(route, from, to) {
    const c = route.coords, cum = route.cum, out = [];
    for (let i = 0; i < c.length; i++) if (cum[i] >= from && cum[i] <= to) out.push(c[i]);
    if (out.length < 2) { const i = cum.findIndex((x) => x >= from); if (i > 0) return [c[i - 1], c[Math.min(i, c.length - 1)]]; }
    return out;
  }

  // ------------------------------------------------------------ map
  const map = L.map('map', { zoomControl: false, attributionControl: true }).setView([53.0, -1.6], 6);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map);
  const layers = { routes: L.layerGroup().addTo(map), hazards: L.layerGroup().addTo(map), gantries: L.layerGroup().addTo(map), reports: L.layerGroup().addTo(map), div: L.layerGroup().addTo(map), me: L.layerGroup().addTo(map) };
  map.on('zoomend', () => drawHazards());
  map.on('dragstart', () => { if (driving()) { S.follow = false; $('recenter').classList.remove('hidden'); } });
  $('recenter').onclick = () => { S.follow = true; $('recenter').classList.add('hidden'); if (S.state.position) map.setView(ll(S.state.position), 16); else call('locate'); };

  const sevClass = (s) => `sev-${s || 'low'}`;
  const driving = () => S.state.mode === 'gps' || S.state.mode === 'sim';
  const activeRoute = () => S.routes.find((r) => r.id === S.activeId);

  function drawRoutes() {
    layers.routes.clearLayers();
    const act = activeRoute();
    for (const r of S.routes) {
      if (r === act) continue;
      L.polyline(r.coords.map(ll), { color: '#8fa3b5', weight: 6, opacity: 0.7 }).on('click', () => call('selectRoute', r.id)).addTo(layers.routes);
    }
    if (act) {
      L.polyline(act.coords.map(ll), { color: '#0b5394', weight: 11, opacity: 0.35 }).addTo(layers.routes);
      L.polyline(act.coords.map(ll), { color: '#1e88e5', weight: 7 }).addTo(layers.routes);
    }
  }

  function drawHazards() {
    layers.hazards.clearLayers(); layers.gantries.clearLayers();
    const r = activeRoute(); const a = r && S.analysis[r.id];
    if (!a) return;
    for (const it of a.items) {
      if (it.kind === 'zone' || it.end - it.start > 150) L.polyline(slice(r, it.start, Math.max(it.end, it.start + 100)).map(ll), { className: `hz ${sevClass(it.severity)}`, weight: 9 }).addTo(layers.hazards);
      if (it.kind === 'vms') continue;
      L.marker(ll(it.location), { icon: L.divIcon({ className: `ev ${sevClass(it.severity)} ${it.kind === 'camera' ? 'cam' : ''}`, html: it.icon, iconSize: [30, 30] }) })
        .on('click', () => openItem(it.id)).addTo(layers.hazards);
    }
    if (map.getZoom() < 12) return;
    for (const g of a.gantries) {
      if (!g.signal || g.signal.lanes.every((l) => l.aspect === 'blank')) continue;
      L.marker(ll(g.location), { icon: L.divIcon({ className: 'gantry-icon', html: gantryIconHtml(g), iconSize: null }), zIndexOffset: 300 }).addTo(layers.gantries);
    }
  }

  function drawReports() {
    layers.reports.clearLayers();
    for (const r of S.state.reports || []) L.marker([r.lat, r.lon], { icon: L.divIcon({ className: 'ev mine', html: r.icon, iconSize: [28, 28] }) }).addTo(layers.reports);
  }

  function drawMe() {
    layers.me.clearLayers();
    const p = S.state.position; if (!p) return;
    const h = S.state.heading || 0;
    L.marker(ll(p), { icon: L.divIcon({ className: 'me', html: `<div style="transform:rotate(${h}deg)"><svg viewBox="0 0 40 40" width="40" height="40"><circle cx="20" cy="20" r="17" fill="#fff"/><path d="M20 6 L30 30 L20 25 L10 30 Z" fill="#1e88e5"/></svg></div>`, iconSize: [40, 40] }), zIndexOffset: 2000 }).addTo(layers.me);
    if (driving() && S.follow) map.setView(ll(p), Math.max(map.getZoom(), 15), { animate: true });
  }

  // ------------------------------------------------------------ native → page
  window.RA = {
    onRoutes(routes) {
      S.routes = routes.map((r) => ({ ...r, cum: cumDist(r.coords) }));
      drawRoutes();
      if (S.routes.length && !driving()) { map.fitBounds(L.latLngBounds(S.routes[0].coords.map(ll)), { padding: [60, 60] }); openSheet('routes'); }
    },
    onAnalysis(data) {
      S.activeId = data.activeId;
      for (const r of data.routes) S.analysis[r.id] = r.analysis;
      for (const r of S.routes) { const u = data.routes.find((x) => x.id === r.id); if (u) Object.assign(r, { etaMinutes: u.etaMinutes, label: u.label }); }
      drawRoutes(); drawHazards(); refreshSheet();
    },
    onState(st) {
      const prev = S.state; S.state = st;
      if (st.activeId && st.activeId !== S.activeId) { S.activeId = st.activeId; drawRoutes(); drawHazards(); }
      drawMe(); renderDrive(); renderAlert();
      if (JSON.stringify(prev.reports) !== JSON.stringify(st.reports)) drawReports();
      $('busy').classList.toggle('hidden', !st.busy); $('busy').querySelector('span').textContent = st.busy || '';
      if (st.error && st.error !== prev.error) { toast(st.error, 5000); call('clearError'); }
      $('demo-badge').classList.toggle('hidden', !st.demo || !S.routes.length);
      if (st.diversion && !prev.diversion) openSheet('divert');
      if (prev.mode !== st.mode && driving()) { S.follow = true; closeSheet(); }
      if (S.sheet === 'ahead' || S.sheet === 'reports' || (S.sheet === 'routes' && prev.destination !== st.destination)) refreshSheet();
    },
    onAlert(a) {
      if (a.kind === 'turn' && a.key.startsWith('report')) toast(`${a.icon} ${a.title}`);
      if (a.kind === 'camera') toast(`${a.icon} ${a.title} ${a.subtitle}`, 5000);
    },
    onResult(id, data) { const cb = S.cbs[id]; delete S.cbs[id]; cb && cb(data); },
  };

  // ------------------------------------------------------------ search
  let searchT = null;
  $('dest').addEventListener('input', () => {
    clearTimeout(searchT);
    const q = $('dest').value.trim();
    $('clear-dest').classList.toggle('hidden', !q);
    if (q.length < 3) { $('results').classList.add('hidden'); return; }
    searchT = setTimeout(async () => {
      const { results, error } = await withCb('search', q);
      const ul = $('results');
      ul.innerHTML = error ? `<li class="muted">${esc(error)}</li>` : results.map((r, i) => {
        const [a, ...b] = r.name.split(',');
        return `<li data-i="${i}"><b>${esc(a)}</b><small>${esc(b.slice(0, 3).join(','))}</small></li>`;
      }).join('') || '<li class="muted">No results</li>';
      ul.classList.remove('hidden');
      ul.onclick = (e) => {
        const li = e.target.closest('li[data-i]'); if (!li) return;
        const r = results[Number(li.dataset.i)];
        $('dest').value = r.name.split(',')[0]; ul.classList.add('hidden'); $('dest').blur();
        call('plan', JSON.stringify({ to: r }));
      };
    }, 450);
  });
  $('clear-dest').onclick = () => { $('dest').value = ''; $('results').classList.add('hidden'); $('clear-dest').classList.add('hidden'); };

  // ------------------------------------------------------------ driving UI
  function renderDrive() {
    const st = S.state, isDriving = driving();
    document.body.classList.toggle('driving', isDriving);
    $('searchbar').classList.toggle('hidden', isDriving);
    $('hud').classList.toggle('hidden', !isDriving);
    $('ahead-btn').classList.toggle('hidden', !activeRoute());
    const n = st.nextStep;
    $('turn').classList.toggle('hidden', !isDriving || !n);
    if (n) { $('turn-arrow').textContent = n.arrow; $('turn-dist').textContent = fmtMi(n.distance); $('turn-text').textContent = n.text; }
    if (isDriving) {
      $('speed').querySelector('b').textContent = st.speedMs != null ? Math.round(st.speedMs * 2.23694) : '–';
      const lim = $('limit'); lim.classList.toggle('hidden', !st.limit); lim.querySelector('span').textContent = st.limit || '';
      $('speed').classList.toggle('over', !!(st.limit && st.speedMs && st.speedMs * 2.23694 > st.limit + 2));
      const eta = new Date(Date.now() + (st.remainingMinutes || 0) * 60000).toLocaleTimeString('en-GB', { timeStyle: 'short' });
      $('eta').innerHTML = `<b>${eta}</b> · ${mins(st.remainingMinutes || 0)} · ${fmtMi(st.remaining || 0)}`;
      $('road').textContent = st.road || '';
    }
    const a = activeRoute() && S.analysis[S.activeId];
    const count = a ? a.items.filter((i) => (i.kind === 'zone' || i.kind === 'event') && i.end > (st.along || 0)).length : 0;
    $('ahead-count').textContent = count ? count : '';
  }

  function exitLine(it, along) {
    const ex = (it.exitsBefore || []).find((x) => x.along > along + 100);
    if (!ex) return along > 0 && it.exitBefore ? '<span class="noexit">No exits left before this</span>' : '';
    return `<span class="exit">Last exit: <b>${esc(ex.ref || 'next exit')}</b> in ${fmtMi(ex.along - along)}</span>`;
  }

  function findItem(id) { const a = S.analysis[S.activeId]; return a && a.items.find((i) => i.id === id); }

  function renderAlert() {
    const st = S.state, a = st.lastAlert, box = $('alert');
    const it = a && a.itemId && findItem(a.itemId);
    if (!a || a.kind !== 'hazard' || !it || !driving()) { box.classList.add('hidden'); return; }
    const d = it.start - st.along;
    box.className = sevClass(it.severity);
    $('alert-icon').textContent = it.icon;
    $('alert-dist').textContent = d > 0 ? fmtMi(d) : 'NOW';
    $('alert-title').textContent = it.headline;
    $('alert-sub').innerHTML = [it.cause && esc(it.cause), it.delayMinutes ? `+${Math.round(it.delayMinutes)} min` : null, exitLine(it, st.along)].filter(Boolean).join(' · ');
    const gs = (it.gantries || []).filter((g) => g.along > st.along - 50);
    const g = gs.find((x) => x.lanesClosed.length) || gs.reduce((p, x) => (!p || (x.speedLimit || 99) < (p.speedLimit || 99) ? x : p), null);
    $('alert-sign').innerHTML = g ? `${gantryHtml(g.signal, { compact: true })}<small>in ${fmtMi(Math.max(0, g.along - st.along))}</small>` : '';
    $('alert-avoid').classList.toggle('hidden', d < 300);
    $('alert-avoid').onclick = () => call('divert', it.id);
  }
  $('alert-stay').onclick = () => call('dismissAlert');
  $('stop').onclick = () => call('stopDrive');

  // ------------------------------------------------------------ bottom sheet
  function openSheet(kind, arg) { S.sheet = kind; S.sheetArg = arg; $('sheet').classList.remove('hidden'); refreshSheet(); }
  function closeSheet() { S.sheet = null; $('sheet').classList.add('hidden'); layers.div.clearLayers(); }
  $('sheet').querySelector('.grab').onclick = closeSheet;
  map.on('click', () => { if (S.sheet && S.sheet !== 'divert') closeSheet(); $('results').classList.add('hidden'); });

  const REPORTS = [
    ['ACCIDENT', '💥', 'Accident'], ['STOPPED_VEHICLE', '🚙', 'Car stopped'], ['LANE_BLOCKED', '🚧', 'Lane blocked'],
    ['OBJECT', '📦', 'Object on road'], ['TRAFFIC', '🚗', 'Traffic jam'], ['POLICE', '🚓', 'Police'],
    ['MOBILE_CAMERA', '📸', 'Mobile camera'], ['SPEED_CAMERA', '📷', 'Fixed camera'], ['ROADWORKS', '🚧', 'Roadworks'],
    ['WEATHER', '🌧', 'Weather'], ['CLOSURE', '⛔', 'Road closed'],
  ];

  function refreshSheet() {
    const body = $('sheet-body'), st = S.state;
    switch (S.sheet) {
      case 'routes': {
        body.innerHTML = `<h3>Routes to ${esc((st.destination || '').split(',')[0])}</h3>` + S.routes.map((r) => {
          const a = S.analysis[r.id] || { items: [], totalDelayMinutes: 0, worstSeverity: 'low' };
          const n = a.items.filter((i) => i.kind === 'zone' || i.kind === 'event').length;
          const cams = a.items.filter((i) => i.kind === 'camera').length;
          return `<button class="route ${r.id === S.activeId ? 'active' : ''}" data-id="${r.id}">
            <div><b>${mins(r.etaMinutes || r.duration / 60)}</b> <span class="muted">${fmtMi(r.distance)}</span>
              <span class="tag ${sevClass(a.worstSeverity)}">${a.totalDelayMinutes ? '+' + a.totalDelayMinutes + ' min' : 'clear'}</span></div>
            <small>${esc(r.label)} · via ${esc(r.summary || 'local roads')} · ${n} item(s) · ${cams} camera(s)</small></button>`;
        }).join('') + `<div class="row"><button class="btn primary big" id="go">▶ Go</button><button class="btn" id="sim">Simulate</button>
          <select id="simspeed"><option value="4">4×</option><option value="10" selected>10×</option><option value="30">30×</option></select></div>
          <button class="btn ghost full" id="show-ahead">What's on this route</button>`;
        body.querySelectorAll('.route').forEach((b) => (b.onclick = () => call('selectRoute', b.dataset.id)));
        $('go').onclick = () => call('startDrive', 'gps', 1);
        $('sim').onclick = () => call('startDrive', 'sim', Number($('simspeed').value));
        $('show-ahead').onclick = () => openSheet('ahead');
        break;
      }
      case 'report': {
        body.innerHTML = '<h3>Report</h3><div class="grid">' + REPORTS.map(([k, i, l]) => `<button class="tile" data-k="${k}"><span>${i}</span>${l}</button>`).join('') +
          '</div><p class="muted small">Saved at your position and direction. Reports warn you on later drives on this road; fixed cameras can be sent to OpenStreetMap from My reports.</p>';
        body.querySelectorAll('.tile').forEach((b) => (b.onclick = () => { call('report', b.dataset.k); closeSheet(); }));
        break;
      }
      case 'ahead': {
        const r = activeRoute(), a = r && S.analysis[r.id], along = st.along || 0;
        const items = a ? a.items.filter((i) => i.end >= along - 50) : [];
        body.innerHTML = `<h3>Road ahead <small class="muted">${a ? '~' + a.totalDelayMinutes + ' min delays' : ''}</small></h3>` +
          (items.length ? '<ol class="ahead">' + items.map((it) => {
            const d = it.start - along;
            const signs = it.kind === 'zone' ? `<div class="strip">${pick(it.gantries).map((g) => `<div><small>${g.along > along ? fmtMi(g.along - along) : 'passed'}</small>${gantryHtml(g.signal, { compact: true })}</div>`).join('')}</div>` : '';
            const vms = it.events.filter((e) => e.vmsText).map((e) => vmsHtml(e.vmsText)).join('');
            return `<li class="${sevClass(it.severity)} k-${it.kind}" data-id="${esc(it.id)}">
              <div class="d">${d > 0 ? fmtMi(d) : 'NOW'}</div>
              <div class="m"><b>${it.icon} ${esc(it.headline)}</b> ${it.demo ? '<span class="badge">DEMO</span>' : ''}${it.userReport ? '<span class="badge you">YOU</span>' : ''}
              ${it.cause ? `<div class="muted small">Cause: ${esc(it.cause)}</div>` : ''}
              <ul>${it.details.slice(0, 3).map((x) => `<li>${esc(x)}</li>`).join('')}</ul>${signs}${vms}
              <div class="foot">${it.delayMinutes ? `<b class="delay">+${Math.round(it.delayMinutes)} min</b>` : ''} ${exitLine(it, along)}
              ${(it.kind === 'zone' || it.kind === 'event') && d > 300 ? `<button class="btn small" data-avoid="${esc(it.id)}">Avoid</button>` : ''}</div></div></li>`;
          }).join('') + '</ol>' : '<p class="clear">✓ Nothing reported on your route ahead</p>');
        body.querySelectorAll('[data-avoid]').forEach((b) => (b.onclick = (e) => { e.stopPropagation(); call('divert', b.dataset.avoid); }));
        body.querySelectorAll('li[data-id]').forEach((li) => (li.onclick = () => { const it = findItem(li.dataset.id); if (it) { S.follow = false; $('recenter').classList.remove('hidden'); map.setView(ll(it.location), 14); } }));
        break;
      }
      case 'divert': {
        const d = st.diversion;
        if (!d) { body.innerHTML = '<h3>Finding a way round…</h3>'; break; }
        layers.div.clearLayers();
        d.options.forEach((o, i) => L.polyline(o.coords.map(ll), { color: i ? '#66bb6a' : '#2e7d32', weight: 6, dashArray: '10 8' }).addTo(layers.div));
        body.innerHTML = `<h3>Avoid: ${esc(d.hazard)}</h3><div class="opt stay">Stay on route · <b>${mins(d.stayMinutes)}</b> <small>(incl. ~${d.stayDelay} min delays)</small></div>` +
          (d.options.length ? d.options.map((o, i) => `<button class="opt" data-i="${i}"><b>${mins(o.etaMinutes)}</b> <span class="${o.deltaMinutes <= 0 ? 'good' : 'bad'}">${o.deltaMinutes <= 0 ? -o.deltaMinutes + ' min faster' : '+' + o.deltaMinutes + ' min'}</span><br><small>${esc(o.label)} · via ${esc(o.summary || 'local roads')}</small></button>`).join('')
            : '<p>No sensible diversion — every way round is slower or hits the same problem.</p>') +
          '<button class="btn ghost full" id="div-cancel">Stay on route</button>';
        body.querySelectorAll('.opt[data-i]').forEach((b) => (b.onclick = () => { call('chooseDiversion', Number(b.dataset.i)); closeSheet(); toast('Diversion selected'); }));
        $('div-cancel').onclick = () => { call('cancelDiversion'); closeSheet(); };
        break;
      }
      case 'menu': {
        const src = (st.sources || []).map((s) => `<li>${esc(s.name)}: ${s.error ? `<span class="bad">${esc(s.error)}</span>` : s.count + ' events'}</li>`).join('');
        body.innerHTML = `<h3>Road Ahead</h3>
          <button class="btn full" id="m-reports">📍 My reports (${(st.reports || []).length})</button>
          <button class="btn full" id="m-settings">⚙ Settings &amp; data feeds</button>
          <button class="btn full" id="m-refresh">↻ Refresh live data</button>
          <h4>Live data</h4><ul class="small">${src || '<li>Not loaded yet</li>'}<li>OpenStreetMap: speed cameras, junction numbers</li>${st.demo ? '<li><span class="badge">DEMO</span> smart-motorway simulator on</li>' : ''}</ul>`;
        $('m-reports').onclick = () => openSheet('reports');
        $('m-settings').onclick = () => openSheet('settings');
        $('m-refresh').onclick = () => { call('refresh'); toast('Refreshing…'); };
        break;
      }
      case 'reports': {
        const rs = (st.reports || []).slice().reverse();
        body.innerHTML = `<h3>My reports</h3>` + (rs.length ? '<ul class="reports">' + rs.map((r) => `<li><span class="ri">${r.icon}</span><div><b>${esc(r.label)}</b>${r.road ? ' · ' + esc(r.road) : ''}<br><small>${new Date(r.createdAt).toLocaleString('en-GB', { dateStyle: 'short', timeStyle: 'short' })}</small></div>
          <div class="ra">${r.type === 'SPEED_CAMERA' ? (r.sharedToOsm ? '<small class="good">sent to OSM</small>' : `<button class="btn small" data-osm="${r.id}">Send to OSM</button>`) : ''}
          <button class="btn small" data-waze="${r.lon},${r.lat}">Waze</button><button class="btn small" data-del="${r.id}">✕</button></div></li>`).join('') + '</ul>' : '<p class="muted">No reports yet. Tap the orange ⚠ button while driving.</p>') +
          `<button class="btn full" id="export">Export as GPX</button><p class="muted small">Waze and Google Maps have no public API for submitting reports, so "Waze" opens Waze at that spot for you to report it there. Fixed cameras can go to OpenStreetMap as a public note for mappers to verify.</p>`;
        body.querySelectorAll('[data-osm]').forEach((b) => (b.onclick = () => call('shareToOsm', b.dataset.osm)));
        body.querySelectorAll('[data-waze]').forEach((b) => (b.onclick = () => { const [lo, la] = b.dataset.waze.split(',').map(Number); call('openInWaze', lo, la); }));
        body.querySelectorAll('[data-del]').forEach((b) => (b.onclick = () => call('deleteReport', b.dataset.del)));
        $('export').onclick = () => call('exportReports');
        break;
      }
      case 'settings': {
        const s = JSON.parse(call('getSettings') || '{}');
        const f = (k, label, type = 'text', ph = '') => type === 'checkbox'
          ? `<label class="chk"><input type="checkbox" data-k="${k}" ${s[k] ? 'checked' : ''}/> ${label}</label>`
          : `<label>${label}<input type="${type}" data-k="${k}" value="${esc(s[k] ?? '')}" placeholder="${esc(ph)}"/></label>`;
        body.innerHTML = `<h3>Settings</h3>
          <label>First warning distance<select data-k="horizonMiles">${[5, 10, 15, 25].map((m) => `<option ${s.horizonMiles == m ? 'selected' : ''}>${m}</option>`).join('')}</select></label>
          ${f('voice', 'Voice alerts', 'checkbox')}${f('turnByTurn', 'Turn-by-turn directions', 'checkbox')}${f('cameras', 'Speed camera alerts (OpenStreetMap)', 'checkbox')}
          ${f('minor', 'Also warn about minor items', 'checkbox')}${f('demo', 'Demo smart-motorway data when no NH feed', 'checkbox')}
          <h4>National Highways NTIS (DATEX II)</h4><p class="muted small">Real gantry signals, message signs and incidents for England. Get the URLs and credentials from your NTIS subscription.</p>
          ${f('nhSituationsUrl', 'Unplanned events URL')}${f('nhPlannedUrl', 'Planned events URL')}${f('nhVmsTableUrl', 'VMS table URL')}${f('nhVmsStatusUrl', 'VMS status URL')}
          ${f('nhSignalTableUrl', 'Signal table URL')}${f('nhSignalStatusUrl', 'Signal status URL')}${f('nhUser', 'Username')}${f('nhPass', 'Password', 'password')}${f('nhKey', 'Subscription key')}
          <h4>Other feeds</h4>${f('tfl', 'TfL London disruptions', 'checkbox')}${f('tflKey', 'TfL app key (optional)')}
          ${f('extraDatexUrls', 'Extra DATEX II URLs (Traffic Scotland/Wales), space separated')}
          <h4>Servers</h4>${f('osrmUrl', 'Routing (OSRM)')}${f('nominatimUrl', 'Search (Nominatim)')}${f('overpassUrl', 'Overpass')}
          <button class="btn primary full" id="save">Save</button>`;
        $('save').onclick = () => {
          const o = {};
          body.querySelectorAll('[data-k]').forEach((el) => { o[el.dataset.k] = el.type === 'checkbox' ? el.checked : el.dataset.k === 'horizonMiles' ? Number(el.value) : el.value; });
          call('saveSettings', JSON.stringify(o)); toast('Saved'); closeSheet();
        };
        break;
      }
    }
  }

  function pick(gs) {
    if (gs.length <= 4) return gs;
    const p = new Set([gs[0]]);
    const m = gs.find((g) => g.signal && g.signal.lanes.some((l) => /merge/.test(l.aspect))); if (m) p.add(m);
    const c = gs.find((g) => g.lanesClosed.length); if (c) p.add(c);
    p.add(gs.reduce((a, b) => ((b.speedLimit || 99) < (a.speedLimit || 99) ? b : a)));
    return [...p].sort((a, b) => a.along - b.along).slice(0, 4);
  }

  function openItem(id) { openSheet('ahead'); setTimeout(() => { const li = document.querySelector(`li[data-id="${CSS.escape(id)}"]`); li && li.scrollIntoView({ block: 'center' }); }, 50); }

  $('report-btn').onclick = () => openSheet('report');
  $('ahead-btn').onclick = () => openSheet(S.sheet === 'ahead' ? null : 'ahead');
  $('menu-btn').onclick = () => openSheet('menu');

  if (!A) console.warn('Android bridge missing — running in a browser');
})();
