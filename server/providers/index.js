// Provider registry: polls every configured live feed on its own interval and
// keeps the latest normalised events in memory.

import { tflProvider } from './tfl.js';
import { datex2Provider } from './datex2Feed.js';
import { demoProvider } from './demo.js';

export function createProviders(config, { fetchImpl = fetch, log = console } = {}) {
  const providers = [];
  if (config.tfl.enabled) providers.push(tflProvider({ appKey: config.tfl.appKey, fetchImpl }));
  for (const feed of config.datex2Feeds) providers.push(datex2Provider(feed, { fetchImpl }));
  const demo = config.demo ? demoProvider() : null;

  const state = new Map(); // name -> {events, lastOk, lastError, count}
  const timers = [];

  async function pollOne(p) {
    const st = state.get(p.name) || { events: [], lastOk: null, lastError: null };
    try {
      st.events = await p.poll();
      st.lastOk = new Date().toISOString();
      st.lastError = null;
    } catch (e) {
      st.lastError = e.message;
      log.warn?.(`[${p.name}] ${e.message}`);
    }
    state.set(p.name, st);
  }

  return {
    demo,
    async start() {
      await Promise.all(providers.map(pollOne));
      for (const p of providers) {
        const t = setInterval(() => pollOne(p), p.intervalMs);
        t.unref?.();
        timers.push(t);
      }
    },
    stop() { timers.forEach(clearInterval); },
    events() {
      const all = [];
      for (const st of state.values()) all.push(...st.events);
      if (demo) all.push(...demo.events());
      return all;
    },
    status() {
      const list = providers.map((p) => {
        const st = state.get(p.name) || {};
        return { name: p.name, label: p.label, live: true, count: st.events?.length || 0, lastOk: st.lastOk || null, lastError: st.lastError || null };
      });
      if (demo) list.push({ name: 'demo', label: 'Demo smart-motorway simulator', live: false, count: demo.events().length, lastOk: new Date().toISOString(), lastError: null });
      return list;
    },
  };
}
