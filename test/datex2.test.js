import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseDatex } from '../server/lib/datex2.js';
import { lanesFromText, kmhToPostedMph } from '../server/lib/events.js';

const fx = (f) => fs.readFileSync(new URL(`./fixtures/${f}`, import.meta.url), 'utf8');

test('situations: accident with linear location, lanes, delay', () => {
  const { events } = parseDatex(fx('situations-v2.xml'), { source: 'nh' });
  const acc = events.find((e) => e.id === 'nh:NH-001-R1');
  assert.ok(acc);
  assert.equal(acc.type, 'accident');
  assert.equal(acc.road, 'M1');
  assert.equal(acc.bearing, 0);
  assert.deepEqual(acc.location, [-0.8, 52.1]);
  assert.deepEqual(acc.end, [-0.81, 52.12]);
  assert.deepEqual(acc.lanesClosed, [1, 2]);
  assert.equal(acc.lanesTotal, 4);
  assert.equal(acc.delayMinutes, 20);
  assert.equal(acc.severity, 'high');
});

test('situations: speed management converts km/h → mph', () => {
  const { events } = parseDatex(fx('situations-v2.xml'), { source: 'nh' });
  const sm = events.find((e) => e.id === 'nh:NH-002-R1');
  assert.equal(sm.type, 'speed_restriction');
  assert.equal(sm.speedLimit, 50);
  assert.equal(sm.bearing, 180);
});

test('VMS table + status: matrix gantry with red X and 40 mph', () => {
  const { events } = parseDatex(fx('vms.xml'), { source: 'nh' });
  const g = events.find((e) => e.type === 'signal');
  assert.ok(g, 'gantry parsed');
  assert.deepEqual(g.location, [-0.78, 52.05]);
  assert.equal(g.bearing, 355);
  assert.deepEqual(g.lanesClosed, [1]);
  assert.equal(g.speedLimit, 40);
  assert.equal(g.signal.lanes.length, 3);
  assert.equal(g.signal.lanes[0].aspect, 'closed');
  assert.equal(g.severity, 'high');
});

test('VMS text sign', () => {
  const { events } = parseDatex(fx('vms.xml'), { source: 'nh' });
  const v = events.find((e) => e.type === 'vms');
  assert.deepEqual(v.vmsText, ['ACCIDENT', 'J14-15', '2 LANES CLOSED']);
  assert.deepEqual(v.location, [-0.775, 52.03]);
});

test('table and status can arrive in separate documents', () => {
  const xml = fx('vms.xml');
  const [tablePart] = xml.split('<payloadPublication xsi:type="VmsPublication"');
  const table = new Map();
  parseDatex(tablePart + '</d2LogicalModel>', { table });
  assert.equal(table.size, 2);
  const statusOnly = xml.replace(/<payloadPublication xsi:type="VmsTablePublication"[\s\S]*?<\/payloadPublication>/, '');
  const { events } = parseDatex(statusOnly, { table });
  assert.equal(events.filter((e) => e.type === 'signal').length, 1);
});

test('lanesFromText', () => {
  assert.deepEqual(lanesFromText('Lanes one and two (of three) are closed').lanesClosed, [1, 2]);
  assert.equal(lanesFromText('Lanes one and two (of three) are closed').lanesTotal, 3);
  assert.deepEqual(lanesFromText('Lane 3 closed due to debris').lanesClosed, [3]);
  assert.deepEqual(lanesFromText('lanes 1 to 3 are closed').lanesClosed, [1, 2, 3]);
  assert.equal(lanesFromText('The road is closed').fullClosure, true);
});

test('kmhToPostedMph', () => {
  assert.equal(kmhToPostedMph(64), 40);
  assert.equal(kmhToPostedMph(80), 50);
  assert.equal(kmhToPostedMph(113), 70);
});
