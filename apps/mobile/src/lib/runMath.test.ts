/**
 * Run tracker distance rules (PRD §8.6).
 *
 *   npx tsx --test apps/mobile/src/lib/runMath.test.ts
 */
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { GeoPoint } from '@th/types';
import { planBatch, uuidV4, type Anchor } from './runMath';

const T0 = 1_790_000_000_000;
const M_PER_DEG_LAT = 111_195;

/** A fix `metres` north of the start line, `seconds` into the run. */
const fix = (metres: number, seconds: number, accuracy = 5): GeoPoint => ({
  lat: 28.6139 + metres / M_PER_DEG_LAT,
  lng: 77.209,
  accuracy,
  altitude: null,
  speed: null,
  timestamp: T0 + seconds * 1000,
});
const anchorAt = (metres: number, seconds: number): Anchor => {
  const p = fix(metres, seconds);
  return { lat: p.lat, lng: p.lng, t: p.timestamp };
};
const near = (actual: number, expected: number, tolerance = 0.5) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} is not within ${tolerance} of ${expected}`);

describe('run distance', () => {
  test('a steady run counts every segment', () => {
    const points = [1, 2, 3, 4, 5].map((i) => fix(i * 13, i * 3));
    const plan = planBatch(anchorAt(0, 0), points, null, null);
    near(plan.addedM, 65);
    assert.equal(plan.store.length, 5);
    assert.equal(plan.dropped, 0);
  });

  test('the first fix of a run anchors it and adds nothing', () => {
    const plan = planBatch(null, [fix(0, 0), fix(13, 3)], null, null);
    assert.equal(plan.store.length, 2);
    near(plan.addedM, 13);
  });

  test('standing still: jitter under 4 m is ignored', () => {
    const plan = planBatch(anchorAt(0, 0), [fix(2, 3), fix(1, 6), fix(3, 9)], null, null);
    assert.equal(plan.addedM, 0);
    assert.equal(plan.store.length, 0);
  });

  test('a poor fix is dropped, never averaged in', () => {
    const plan = planBatch(anchorAt(0, 0), [fix(13, 3), fix(80, 6, 60), fix(26, 9)], null, null);
    near(plan.addedM, 26);
    assert.equal(plan.dropped, 1);
  });

  test('an underpass gap still counts: 150 m in a minute is a run, not a jump', () => {
    const plan = planBatch(anchorAt(0, 0), [fix(150, 60), fix(163, 63)], null, null);
    near(plan.addedM, 163);
    assert.equal(plan.dropped, 0);
  });

  test('a one-off spike is not counted and does not derail the run', () => {
    const points = [fix(13, 3), fix(500, 6), fix(26, 9), fix(39, 12)];
    const plan = planBatch(anchorAt(0, 0), points, null, null);
    near(plan.addedM, 39);
    assert.equal(plan.dropped, 1);
    assert.equal(plan.candidate, null);
  });

  test('after a real relocation the run carries on — the jump itself is not counted', () => {
    // Signal comes back 600 m on, three seconds after the last fix.
    const points = [fix(600, 3), fix(613, 6), fix(626, 9), fix(639, 12)];
    const plan = planBatch(anchorAt(0, 0), points, null, null);
    near(plan.addedM, 39); // 613→626→639 plus the 13 m confirming step
    assert.equal(plan.candidate, null);
    assert.equal(plan.store.at(-1)?.timestamp, fix(639, 12).timestamp);
  });

  test('the confirmation can arrive in the next batch', () => {
    const first = planBatch(anchorAt(0, 0), [fix(600, 3)], null, null);
    assert.equal(first.addedM, 0);
    assert.ok(first.candidate);
    const second = planBatch(anchorAt(0, 0), [fix(613, 6)], null, first.candidate);
    near(second.addedM, 13);
    assert.equal(second.store.length, 2);
  });

  test('after resume, ground covered while paused is not counted', () => {
    const resumedAt = T0 + 600_000;
    const plan = planBatch(anchorAt(0, 0), [fix(300, 600), fix(313, 603)], resumedAt, null);
    near(plan.addedM, 13);
    assert.equal(plan.reanchorAfter, null);
  });

  test('a cached pre-pause fix replayed on resume is ignored, not anchored on', () => {
    // Paused at 570 s having reached 0 m, walked 300 m, resumed at 600 s. The
    // OS replays the 570 s fix first; anchoring on it would make the walk look
    // like a 300 m run in 33 s.
    const resumedAt = T0 + 600_000;
    const plan = planBatch(anchorAt(0, 570), [fix(0, 570), fix(300, 603), fix(313, 606)], resumedAt, null);
    near(plan.addedM, 13);
    assert.equal(plan.store[0]?.timestamp, fix(300, 603).timestamp);
  });

  test('a fix cached from before the run started never becomes its first point', () => {
    const startedAt = T0 + 300_000;
    const plan = planBatch(null, [fix(-2000, 0), fix(0, 303), fix(13, 306)], startedAt, null);
    near(plan.addedM, 13);
    assert.equal(plan.store.length, 2);
  });

  test('a resume that sees only poor fixes keeps waiting to re-anchor', () => {
    const resumedAt = T0 + 600_000;
    const plan = planBatch(anchorAt(0, 0), [fix(300, 600, 80)], resumedAt, null);
    assert.equal(plan.reanchorAfter, resumedAt);
    assert.equal(plan.addedM, 0);
  });
});

describe('run ids', () => {
  test('without crypto.randomUUID (as on Hermes) ids are still RFC 4122 v4 — the server rejects anything else', () => {
    const original = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
    Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true });
    try {
      const ids = new Set(Array.from({ length: 2000 }, () => uuidV4()));
      assert.equal(ids.size, 2000);
      for (const id of ids) {
        assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
      }
    } finally {
      if (original) Object.defineProperty(globalThis, 'crypto', original);
    }
  });
});
