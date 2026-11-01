import test from 'node:test';
import assert from 'node:assert/strict';
import { RouteLine, destination, METRES_PER_MILE } from '../shared/geo.js';
import { buildRoute, analyseRoute } from '../server/lib/analyse.js';
import { demoProvider } from '../server/providers/demo.js';

// A straight 40 km northbound "M1".
const start = [-1.0, 52.0];
const end = destination(start, 0, 40000);
const coords = Array.from({ length: 81 }, (_, i) => destination(start, 0, i * 500));
const steps = [{ from: 0, to: 40000, ref: 'M1', name: '' }];
const line = () => buildRoute(new RouteLine(coords), steps);
const at = (m, side = 0) => (side ? destination(destination(start, 0, m), 90, side) : destination(start, 0, m));

const gantry = (m, speed, closed = [], extra = {}) => ({
  id: `g${m}`, source: 'test', type: 'signal', road: 'M1', bearing: 0, location: at(m, 5),
  speedLimit: speed, lanesClosed: closed, severity: closed.length ? 'high' : 'medium',
  signal: { lanes: [1, 2, 3].map((l) => (closed.includes(l) ? { lane: l, aspect: 'closed' } : { lane: l, aspect: speed ? 'speed' : 'blank', speed })) },
  ...extra,
});

test('consecutive restricted gantries become one zone with closure and delay', () => {
  const events = [
    gantry(10000, null), gantry(11000, 60), gantry(12000, 50), gantry(13000, 40, [1]), gantry(14000, 40, [1]), gantry(15000, null),
    { id: 'acc', source: 'test', type: 'accident', severity: 'high', road: 'M1', bearing: 0, location: at(14500, 10), title: 'Accident', lanesClosed: [1], delayMinutes: 15 },
  ];
  const res = analyseRoute(line(), events);
  const zones = res.items.filter((i) => i.kind === 'zone');
  assert.equal(zones.length, 1);
  const z = zones[0];
  assert.ok(Math.abs(z.start - 11000) < 5);
  assert.ok(Math.abs(z.end - 15000) < 5);
  assert.equal(z.speedLimit, 40);
  assert.deepEqual(z.lanesClosed, [1]);
  assert.ok(Math.abs(z.closureAt - 13000) < 5);
  assert.equal(z.events[0].id, 'acc', 'accident explains the zone');
  assert.equal(z.delayMinutes, 15);
  assert.equal(res.items.filter((i) => i.kind === 'event').length, 0);
});

test('gantries on the opposite carriageway are ignored', () => {
  const events = [gantry(20000, 40, [1, 2], { id: 'sb', bearing: 180, location: at(20000, -25) })];
  const res = analyseRoute(line(), events);
  assert.equal(res.items.length, 0);
});

test('gantry on a different road (bridge over the motorway) is ignored', () => {
  const events = [gantry(20000, 30, [], { id: 'bridge', road: 'A45', bearing: 0, location: at(20000, 30) })];
  assert.equal(analyseRoute(line(), events).items.length, 0);
});

test('last exit before hazard is found', () => {
  const events = [gantry(25000, 50), gantry(26000, 50)];
  const junctions = [{ ref: 'J15', location: at(18000) }, { ref: 'J16', location: at(24900) }, { ref: 'J17', location: at(30000) }];
  const [z] = analyseRoute(line(), events, { junctions }).items;
  assert.equal(z.exitBefore.ref, 'J15', 'J16 is within 250 m of the zone start so too late');
  assert.deepEqual(z.exitsBefore.map((x) => x.ref), ['J15']);
});

test('demo provider builds a scenario that analysis picks up ahead of the driver', () => {
  const demo = demoProvider({ now: () => 0 });
  const l = line();
  // Try a few roads until the seeded RNG gives us a scenario (80% chance each).
  let res = null;
  for (const ref of ['M1', 'M6', 'M25', 'M4', 'M5', 'M62']) {
    const st = [{ from: 0, to: 40000, ref }];
    const l2 = buildRoute(new RouteLine(coords), st);
    demo.prepareRoute(l2, st);
    res = analyseRoute(l2, demo.events());
    if (res.items.some((i) => i.kind === 'zone')) break;
  }
  const z = res.items.find((i) => i.kind === 'zone');
  assert.ok(z, 'a restriction zone was generated');
  assert.ok(z.start > 2 * METRES_PER_MILE, 'zone is far enough ahead to warn');
  assert.ok(z.gantries.length >= 2);
  assert.ok(l);
});
