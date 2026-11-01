// Generic DATEX II feed provider. National Highways (NTIS), Traffic Scotland,
// Traffic Wales etc. are configured as instances of this.
//
// A feed definition:
//   { name, label, urls: { situations?, vmsTable?, vmsStatus? , ...any }, headers?, intervalMs? }
// Any URL may return any DATEX II publication; the parser works out what it is.
// The VMS *table* (sign locations) is fetched less often than status.
import { parseDatex } from '../lib/datex2.js';

export function datex2Provider(feed, { fetchImpl = fetch } = {}) {
  const table = new Map();
  let lastTableFetch = 0;
  const TABLE_REFRESH = 6 * 3600 * 1000;

  async function get(url) {
    const res = await fetchImpl(url, { headers: { accept: 'application/xml', 'user-agent': 'uk-traffic-maps', ...(feed.headers || {}) } });
    if (!res.ok) throw new Error(`${feed.name} HTTP ${res.status} for ${url}`);
    return res.text();
  }

  return {
    name: feed.name,
    label: feed.label || feed.name,
    intervalMs: feed.intervalMs || 60000,
    async poll() {
      const urls = Object.entries(feed.urls || {});
      const tableUrls = urls.filter(([k]) => /table/i.test(k));
      const liveUrls = urls.filter(([k]) => !/table/i.test(k));
      if (tableUrls.length && Date.now() - lastTableFetch > TABLE_REFRESH) {
        for (const [, u] of tableUrls) parseDatex(await get(u), { source: feed.name, table });
        lastTableFetch = Date.now();
      }
      const events = [];
      for (const [, u] of liveUrls) events.push(...parseDatex(await get(u), { source: feed.name, table }).events);
      return events;
    },
  };
}
