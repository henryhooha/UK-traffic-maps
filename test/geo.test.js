import test from 'node:test';
import assert from 'node:assert/strict';
import { haversine, bearing, RouteLine, directionToBearing, destination } from '../shared/geo.js';

test('haversine: London to Birmingham ≈ 163 km', () => {
  const d = haversine([-0.1276, 51.5072], [-1.8904, 52.4862]);
  assert.ok(Math.abs(d - 163000) < 2000, `got ${d}`);
});

test('bearing: due north / east', () => {
  assert.ok(Math.abs(bearing([0, 50], [0, 51])) < 0.01);
  assert.ok(Math.abs(bearing([0, 50], [1, 50]) - 90) < 1);
});

test('RouteLine.snap finds distance along and respects bearing', () => {
  const line = new RouteLine([[0, 50], [0, 50.1], [0, 50.2]]); // northbound ~22 km
  const p = destination([0, 50.05], 90, 20); // 20 m east of the line
  const hit = line.snap(p, { maxDist: 50 });
  assert.ok(hit);
  assert.ok(Math.abs(hit.along - haversine([0, 50], [0, 50.05])) < 5);
  assert.ok(hit.offset > 15 && hit.offset < 25);
  assert.equal(line.snap(p, { maxDist: 50, bearing: 180, maxBearingDiff: 45 }), null, 'southbound thing should not match');
  assert.equal(line.snap(p, { maxDist: 10 }), null);
});

test('RouteLine.pointAt / slice', () => {
  const line = new RouteLine([[0, 50], [0, 51]]);
  const mid = line.pointAt(line.length / 2);
  assert.ok(Math.abs(mid[1] - 50.5) < 1e-6);
  assert.equal(line.slice(0, line.length / 2).length, 2);
});

test('directionToBearing', () => {
  assert.equal(directionToBearing('northBound'), 0);
  assert.equal(directionToBearing('Southbound'), 180);
  assert.equal(directionToBearing('clockwise'), null);
});
