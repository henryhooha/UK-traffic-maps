// All configuration comes from environment variables (see .env.example).
import fs from 'node:fs';
import path from 'node:path';

// Minimal .env loader so there's no dependency on dotenv.
function loadDotEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (!m || line.trim().startsWith('#')) continue;
    let v = m[2];
    if (/^(['"]).*\1$/.test(v)) v = v.slice(1, -1);
    if (!(m[1] in process.env)) process.env[m[1]] = v;
  }
}
loadDotEnv(path.resolve(process.cwd(), '.env'));

const env = process.env;
const bool = (v, d) => (v == null || v === '' ? d : /^(1|true|yes|on)$/i.test(v));

function datex2Feeds() {
  const feeds = [];
  // National Highways NTIS (England) — DATEX II. Requires an NTIS subscription.
  if (env.NH_SITUATIONS_URL || env.NH_VMS_STATUS_URL) {
    const headers = {};
    if (env.NH_USERNAME) headers.authorization = 'Basic ' + Buffer.from(`${env.NH_USERNAME}:${env.NH_PASSWORD || ''}`).toString('base64');
    if (env.NH_SUBSCRIPTION_KEY) headers['Ocp-Apim-Subscription-Key'] = env.NH_SUBSCRIPTION_KEY;
    feeds.push({
      name: 'national-highways',
      label: 'National Highways (NTIS)',
      headers,
      intervalMs: Number(env.NH_POLL_MS || 60000),
      urls: Object.fromEntries(Object.entries({
        situations: env.NH_SITUATIONS_URL,
        planned: env.NH_PLANNED_URL,
        vmsTable: env.NH_VMS_TABLE_URL,
        vmsStatus: env.NH_VMS_STATUS_URL,
        signalTable: env.NH_SIGNAL_TABLE_URL,
        signalStatus: env.NH_SIGNAL_STATUS_URL,
      }).filter(([, v]) => v)),
    });
  }
  // Traffic Scotland — DATEX II.
  if (env.TS_SITUATIONS_URL || env.TS_VMS_STATUS_URL) {
    const headers = {};
    if (env.TS_USERNAME) headers.authorization = 'Basic ' + Buffer.from(`${env.TS_USERNAME}:${env.TS_PASSWORD || ''}`).toString('base64');
    feeds.push({
      name: 'traffic-scotland',
      label: 'Traffic Scotland',
      headers,
      urls: Object.fromEntries(Object.entries({
        situations: env.TS_SITUATIONS_URL,
        roadworks: env.TS_ROADWORKS_URL,
        vmsTable: env.TS_VMS_TABLE_URL,
        vmsStatus: env.TS_VMS_STATUS_URL,
      }).filter(([, v]) => v)),
    });
  }
  // Anything else: DATEX2_FEEDS='[{"name":"traffic-wales","urls":{"situations":"https://..."},"headers":{}}]'
  if (env.DATEX2_FEEDS) {
    try { feeds.push(...JSON.parse(env.DATEX2_FEEDS)); } catch (e) { console.warn('DATEX2_FEEDS is not valid JSON:', e.message); }
  }
  return feeds;
}

const feeds = datex2Feeds();

export const config = {
  port: Number(env.PORT || 3000),
  osrmUrl: env.OSRM_URL || 'https://router.project-osrm.org',
  nominatimUrl: env.NOMINATIM_URL || 'https://nominatim.openstreetmap.org',
  overpassUrl: env.OVERPASS_URL || 'https://overpass-api.de/api/interpreter',
  overpass: bool(env.ENABLE_OVERPASS, true),
  contactEmail: env.CONTACT_EMAIL || '',
  tfl: { enabled: bool(env.ENABLE_TFL, true), appKey: env.TFL_APP_KEY || '' },
  datex2Feeds: feeds,
  // Demo simulator: on unless a live smart-motorway feed is configured, or forced with DEMO_MODE.
  demo: bool(env.DEMO_MODE, feeds.length === 0),
};
