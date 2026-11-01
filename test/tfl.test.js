import test from 'node:test';
import assert from 'node:assert/strict';
import { parseTfl } from '../server/providers/tfl.js';

test('parseTfl maps a TfL disruption', () => {
  const [e] = parseTfl([{
    id: 'TIMS-1', point: '[-0.2255,51.5798]', severity: 'Serious', category: 'Works', subCategory: 'Utility works',
    comments: '[A41] Hendon Way (Northbound) at the junction of Elliot Road - Lanes one and two (of three) are closed due to emergency works.',
    location: '[A41] HENDON WAY', hasClosures: false, startDateTime: '2026-10-28T17:00:00Z', endDateTime: '2026-11-01T23:59:00Z',
  }]);
  assert.equal(e.id, 'tfl:TIMS-1');
  assert.equal(e.type, 'roadworks');
  assert.equal(e.severity, 'high');
  assert.equal(e.road, 'A41');
  assert.equal(e.bearing, 0);
  assert.deepEqual(e.lanesClosed, [1, 2]);
  assert.equal(e.lanesTotal, 3);
});
