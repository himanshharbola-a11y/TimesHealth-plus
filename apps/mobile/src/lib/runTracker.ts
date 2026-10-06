import * as Location from 'expo-location';
import * as TaskManager from 'expo-task-manager';
import * as SQLite from 'expo-sqlite';
import type { GeoPoint } from '@th/types';
import { UUID_RE, planBatch, uuidV4 } from './runMath';

/**
 * Run tracker engine — PRD §8.6, marked do-not-cut.
 *
 * "Full GPS tracking, not a stopwatch." The design prototype shipped a
 * coroutine incrementing a float; this is the real thing.
 *
 * Four requirements from §8.6 drive every design decision here:
 *
 *  1. "Background tracking and screen-off must keep recording."
 *     → a foreground service with a persistent notification. We deliberately
 *       do NOT request ACCESS_BACKGROUND_LOCATION: a foreground service started
 *       while the app is visible is enough, and avoids Play's background
 *       location declaration review (see docs/03 §6).
 *
 *  2. "App killed mid-run → run must be recoverable, not lost."
 *     → every point is written to SQLite as it arrives. Nothing is held only
 *       in memory. On relaunch, an unfinished run is found and resumed.
 *
 *  3. "Poor GPS → do not silently record a wrong distance."
 *     → points above ACCURACY_GATE_M are dropped rather than averaged in, and
 *       a run that drops too many is flagged with hasAccuracyWarning so the UI
 *       can say so instead of inventing distance.
 *
 *  4. Long runs are a battery concern → distance-interval sampling rather than
 *       a tight timer, and GPS accuracy rather than best-for-navigation.
 *
 * The distance rules themselves live in runMath.ts, pure and unit-tested.
 */

export const LOCATION_TASK = 'timeshealth-run-tracking';

/**
 * ONE connection for the life of the process, and the open itself is shared.
 *
 * Caching only the result let two callers that arrived together (the panel
 * mounting while the location task fired) open the file twice. expo-sqlite
 * hands both JS objects the same native connection, and when the orphaned one
 * is garbage-collected it closes that connection under the survivor — every
 * later write then failed with "NativeDatabase.prepareAsync has been rejected
 * (NullPointerException)" and runs silently stopped recording.
 *
 * `useNewConnection` keeps this connection out of the native cache, so nothing
 * else can ever share — and release — it.
 */
let dbPromise: Promise<SQLite.SQLiteDatabase> | null = null;

function getDb(): Promise<SQLite.SQLiteDatabase> {
  dbPromise ??= openDb().catch((e: unknown) => {
    dbPromise = null; // let the next call retry rather than cache the failure
    throw e;
  });
  return dbPromise;
}

async function openDb(): Promise<SQLite.SQLiteDatabase> {
  const db = await SQLite.openDatabaseAsync('timeshealth.db', { useNewConnection: true });
  await db.execAsync(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS runs (
      id TEXT PRIMARY KEY NOT NULL,
      started_at INTEGER NOT NULL,
      ended_at INTEGER,
      distance_m REAL NOT NULL DEFAULT 0,
      moving_ms INTEGER NOT NULL DEFAULT 0,
      dropped_points INTEGER NOT NULL DEFAULT 0,
      total_points INTEGER NOT NULL DEFAULT 0,
      state TEXT NOT NULL DEFAULT 'RUNNING',
      synced INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS run_points (
      run_id TEXT NOT NULL,
      t INTEGER NOT NULL,
      lat REAL NOT NULL,
      lng REAL NOT NULL,
      accuracy REAL NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_points_run ON run_points(run_id, t);
  `);
  await migrate(db);
  return db;
}

/** Versioned, additive schema changes — runs already on a phone must survive an update. */
async function migrate(db: SQLite.SQLiteDatabase): Promise<void> {
  const row = await db.getFirstAsync<{ user_version: number }>('PRAGMA user_version');
  const version = row?.user_version ?? 0;
  if (version < 1) {
    // v1: pause bookkeeping, so time spent paused is not counted as running,
    // and `reanchor` — the start/resume time (epoch ms, 0 = none) before which
    // fixes are ignored, so ground covered while paused is not counted either.
    await db.execAsync(`
      ALTER TABLE runs ADD COLUMN paused_ms INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE runs ADD COLUMN paused_at INTEGER;
      ALTER TABLE runs ADD COLUMN reanchor INTEGER NOT NULL DEFAULT 0;
      PRAGMA user_version = 1;
    `);
  }
  if (version < 2) {
    // v2: re-key runs saved under the old non-UUID ids, which the server
    // rejects — otherwise they would be retried, and refused, forever.
    const rows = await db.getAllAsync<{ id: string }>('SELECT id FROM runs');
    for (const { id } of rows) {
      if (UUID_RE.test(id)) continue;
      const fresh = uuidV4();
      await db.runAsync('UPDATE run_points SET run_id = ? WHERE run_id = ?', fresh, id);
      await db.runAsync('UPDATE runs SET id = ? WHERE id = ?', fresh, id);
    }
    await db.execAsync('PRAGMA user_version = 2;');
  }
  if (version < 3) {
    // v3: whose run it is. On a shared phone, one person's unsynced runs must
    // never upload into — or show up for — the next person who signs in.
    // Pre-v3 rows have no owner ('') and are treated as the current user's.
    await db.execAsync(`
      ALTER TABLE runs ADD COLUMN owner TEXT NOT NULL DEFAULT '';
      PRAGMA user_version = 3;
    `);
  }
}

export interface ActiveRun {
  id: string;
  /** The signed-in user (server user id) the run belongs to; '' for pre-v3 rows. */
  owner: string;
  startedAt: number;
  distanceM: number;
  /** Total time spent paused, excluding a pause still in progress. */
  pausedMs: number;
  /** When the current pause began; null while running. */
  pausedAt: number | null;
  state: 'RUNNING' | 'PAUSED';
  droppedPoints: number;
  totalPoints: number;
}

/** Time actually spent running — what the timer shows and pace is based on. */
export function movingSeconds(run: ActiveRun, now: number = Date.now()): number {
  const pausedNow = run.pausedAt !== null ? now - run.pausedAt : 0;
  return Math.max(0, Math.round((now - run.startedAt - run.pausedMs - pausedNow) / 1000));
}

/**
 * Appends a batch of fixes to the active run and recomputes distance.
 * Called from the background task, so it must not touch React state.
 *
 * Batches are applied one at a time: two overlapping batches would both
 * measure from the same "last point" and count that stretch twice.
 */
let ingestQueue: Promise<void> = Promise.resolve();

export function ingestPoints(points: GeoPoint[]): Promise<void> {
  const next = ingestQueue.then(() => ingestBatch(points));
  ingestQueue = next.catch(() => undefined);
  return next;
}

/**
 * A fix that implied an impossible speed, held until the next one decides
 * whether it was a one-off error or the runner's real new position.
 */
let jumpCandidate: { runId: string; point: GeoPoint } | null = null;

async function ingestBatch(points: GeoPoint[]): Promise<void> {
  const database = await getDb();
  const run = await getActiveRun();
  if (!run || run.state !== 'RUNNING') return;

  const last = await database.getFirstAsync<{ lat: number; lng: number; t: number }>(
    'SELECT lat, lng, t FROM run_points WHERE run_id = ? ORDER BY t DESC LIMIT 1',
    run.id,
  );
  const flags = await database.getFirstAsync<{ reanchor: number }>(
    'SELECT reanchor FROM runs WHERE id = ?',
    run.id,
  );

  const plan = planBatch(
    last ?? null,
    points,
    flags && flags.reanchor > 0 ? flags.reanchor : null,
    jumpCandidate?.runId === run.id ? jumpCandidate.point : null,
  );

  for (const p of plan.store) {
    await database.runAsync(
      'INSERT INTO run_points (run_id, t, lat, lng, accuracy) VALUES (?, ?, ?, ?, ?)',
      run.id,
      p.timestamp,
      p.lat,
      p.lng,
      p.accuracy,
    );
  }
  jumpCandidate = plan.candidate ? { runId: run.id, point: plan.candidate } : null;

  await database.runAsync(
    `UPDATE runs
       SET distance_m = distance_m + ?,
           dropped_points = dropped_points + ?,
           total_points = total_points + ?,
           reanchor = ?
     WHERE id = ?`,
    plan.addedM,
    plan.dropped,
    plan.total,
    plan.reanchorAfter ?? 0,
    run.id,
  );
}

// The background task. Registered at module load so it survives an app kill
// and is available when the OS relaunches the process to deliver locations.
TaskManager.defineTask(LOCATION_TASK, async ({ data, error }) => {
  if (error || !data) return;
  const { locations } = data as { locations: Location.LocationObject[] };
  if (!locations?.length) return;

  await ingestPoints(
    locations.map((l) => ({
      lat: l.coords.latitude,
      lng: l.coords.longitude,
      accuracy: l.coords.accuracy ?? 999,
      altitude: l.coords.altitude,
      speed: l.coords.speed,
      timestamp: l.timestamp,
    })),
  );
});

export async function getActiveRun(): Promise<ActiveRun | null> {
  const database = await getDb();
  const row = await database.getFirstAsync<{
    id: string;
    owner: string;
    started_at: number;
    distance_m: number;
    paused_ms: number;
    paused_at: number | null;
    state: string;
    dropped_points: number;
    total_points: number;
  }>("SELECT * FROM runs WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1");
  if (!row) return null;
  const paused = row.state === 'PAUSED';
  return {
    id: row.id,
    owner: row.owner,
    startedAt: row.started_at,
    distanceM: row.distance_m,
    pausedMs: row.paused_ms,
    pausedAt: paused ? row.paused_at : null,
    state: paused ? 'PAUSED' : 'RUNNING',
    droppedPoints: row.dropped_points,
    totalPoints: row.total_points,
  };
}

export type PermissionOutcome = 'granted' | 'denied' | 'blocked';

export async function ensurePermission(): Promise<PermissionOutcome> {
  const current = await Location.getForegroundPermissionsAsync();
  if (current.granted) return 'granted';
  // §8.6: denied → explain and route to settings. "Blocked" is the case where
  // the OS will not show the prompt again, so a retry button would be a lie.
  if (!current.canAskAgain) return 'blocked';
  const res = await Location.requestForegroundPermissionsAsync();
  if (res.granted) return 'granted';
  return res.canAskAgain ? 'denied' : 'blocked';
}

/**
 * The unfinished run the CURRENT user can see, or null. Another person's
 * unfinished run on this phone (they signed out mid-run) stays parked for
 * them; it is never shown to, or uploaded as, someone else.
 */
export async function getActiveRunFor(owner: string): Promise<ActiveRun | null> {
  const run = await getActiveRun();
  return run && (run.owner === '' || run.owner === owner) ? run : null;
}

/**
 * Called when the tracker opens and finds an unfinished run (§8.6: "app killed
 * mid-run → run must be recoverable"). If the process died, the foreground
 * service that delivered GPS fixes died with it — so the run is NOT still
 * recording, whatever its row says. Showing it as live would freeze the
 * distance while the clock kept counting. Instead the dead stretch is banked
 * as a pause (from the last recorded fix) and the runner taps Resume, which
 * restarts tracking and re-anchors on their real position.
 */
export async function recoverActiveRun(owner: string): Promise<ActiveRun | null> {
  const run = await getActiveRunFor(owner);
  if (!run || run.state !== 'RUNNING') return run;
  let tracking = false;
  try {
    tracking = await Location.hasStartedLocationUpdatesAsync(LOCATION_TASK);
  } catch {
    tracking = false;
  }
  if (tracking) return run;

  const database = await getDb();
  const last = await database.getFirstAsync<{ t: number }>(
    'SELECT t FROM run_points WHERE run_id = ? ORDER BY t DESC LIMIT 1',
    run.id,
  );
  // Never before the last resume (`reanchor`): the gap before it was already
  // counted as a pause, and counting it twice would eat real running time.
  const flags = await database.getFirstAsync<{ reanchor: number }>(
    'SELECT reanchor FROM runs WHERE id = ?',
    run.id,
  );
  const pausedAt = Math.max(run.startedAt, last?.t ?? run.startedAt, flags?.reanchor ?? 0);
  await database.runAsync("UPDATE runs SET state = 'PAUSED', paused_at = ? WHERE id = ?", pausedAt, run.id);
  return { ...run, state: 'PAUSED', pausedAt };
}

/**
 * Sign-out on a shared phone: stop GPS (the foreground service must not keep
 * tracking for someone who has left) and park any unfinished run as paused.
 * Runs stay on the device under their owner and sync when they sign back in.
 */
export async function suspendTrackingForSignOut(): Promise<void> {
  await stopUpdates();
  const run = await getActiveRun();
  if (run && run.state === 'RUNNING') {
    const database = await getDb();
    await database.runAsync("UPDATE runs SET state = 'PAUSED', paused_at = ? WHERE id = ?", Date.now(), run.id);
  }
}

export async function startRun(owner: string): Promise<ActiveRun> {
  const database = await getDb();
  const existing = await getActiveRunFor(owner);
  if (existing) return existing;
  // Someone else's unfinished run is parked, not overwritten: finish it as
  // theirs so it can still sync when they sign back in.
  const other = await getActiveRun();
  if (other) {
    await database.runAsync(
      "UPDATE runs SET ended_at = ?, state = 'FINISHED', paused_at = NULL WHERE id = ?",
      other.pausedAt ?? Date.now(),
      other.id,
    );
  }

  const id = uuidV4();
  const startedAt = Date.now();
  // reanchor = startedAt: a cached fix from before the run (say, from home
  // before driving to the park) must not become its first point.
  await database.runAsync(
    'INSERT INTO runs (id, owner, started_at, state, reanchor) VALUES (?, ?, ?, ?, ?)',
    id,
    owner,
    startedAt,
    'RUNNING',
    startedAt,
  );

  await Location.startLocationUpdatesAsync(LOCATION_TASK, TRACKING_OPTIONS);

  return {
    id,
    owner,
    startedAt,
    distanceM: 0,
    pausedMs: 0,
    pausedAt: null,
    state: 'RUNNING',
    droppedPoints: 0,
    totalPoints: 0,
  };
}

const TRACKING_OPTIONS: Location.LocationTaskOptions = {
  // GPS-grade fixes. Not BestForNavigation: on iOS that adds sensor fusion
  // meant for driving and costs battery a two-hour run can't spare (req. 4).
  accuracy: Location.Accuracy.High,
  // Sample by distance rather than a tight timer — materially kinder to the
  // battery on a long run.
  distanceInterval: 5,
  timeInterval: 3000,
  pausesUpdatesAutomatically: false,
  // Requirement 1: a foreground service keeps recording with the screen off,
  // and the persistent notification is what Android requires in exchange.
  foregroundService: {
    notificationTitle: 'TimesHealth+ is tracking your run',
    notificationBody: 'Distance, time and pace are being recorded.',
    notificationColor: '#E8533A',
  },
  showsBackgroundLocationIndicator: true,
};

export async function pauseRun(): Promise<void> {
  const database = await getDb();
  const run = await getActiveRun();
  if (!run || run.state === 'PAUSED') return;
  await database.runAsync(
    "UPDATE runs SET state = 'PAUSED', paused_at = ? WHERE id = ?",
    Date.now(),
    run.id,
  );
  await stopUpdates();
}

export async function resumeRun(): Promise<void> {
  const database = await getDb();
  const run = await getActiveRun();
  if (!run) return;
  // Bank the pause, and re-anchor on the first fix newer than now so wherever
  // the runner walked while paused is not added to the run.
  const now = Date.now();
  const pausedFor = run.pausedAt !== null ? now - run.pausedAt : 0;
  await database.runAsync(
    `UPDATE runs
        SET state = 'RUNNING', paused_ms = paused_ms + ?, paused_at = NULL, reanchor = ?
      WHERE id = ?`,
    pausedFor,
    now,
    run.id,
  );
  await Location.startLocationUpdatesAsync(LOCATION_TASK, TRACKING_OPTIONS);
}

async function stopUpdates(): Promise<void> {
  try {
    const registered = await TaskManager.isTaskRegisteredAsync(LOCATION_TASK);
    if (registered) await Location.stopLocationUpdatesAsync(LOCATION_TASK);
  } catch {
    // Already stopped.
  }
}

export interface FinishedRun {
  id: string;
  startedAt: number;
  endedAt: number;
  /** Unrounded — the "too short to save" check must not see 6 m as 0.01 km. */
  distanceM: number;
  distanceKm: number;
  durationSeconds: number;
  avgPaceSecPerKm: number;
  caloriesBurned: number;
  routePolyline: string | null;
  hasAccuracyWarning: boolean;
}

export async function finishRun(): Promise<FinishedRun | null> {
  const database = await getDb();
  const run = await getActiveRun();
  if (!run) return null;

  await stopUpdates();
  const endedAt = Date.now();
  // Finishing straight from a pause: that last pause isn't running time either.
  const pausedMs = run.pausedMs + (run.pausedAt !== null ? endedAt - run.pausedAt : 0);
  await database.runAsync(
    "UPDATE runs SET ended_at = ?, state = 'FINISHED', paused_ms = ?, paused_at = NULL WHERE id = ?",
    endedAt,
    pausedMs,
    run.id,
  );

  const points = await database.getAllAsync<{ lat: number; lng: number }>(
    'SELECT lat, lng FROM run_points WHERE run_id = ? ORDER BY t ASC',
    run.id,
  );

  // Moving time: pace over a run with a ten-minute pause at a signal must not
  // include the ten minutes.
  const durationSeconds = Math.max(1, Math.round((endedAt - run.startedAt - pausedMs) / 1000));
  const distanceKm = Math.round((run.distanceM / 1000) * 100) / 100;
  const avgPaceSecPerKm = distanceKm > 0 ? Math.round(durationSeconds / distanceKm) : 0;

  // Flag rather than fabricate: if we discarded a meaningful share of fixes,
  // the distance is not trustworthy and the UI must say so (§8.6).
  const hasAccuracyWarning =
    run.totalPoints > 0 && run.droppedPoints / run.totalPoints > 0.25;

  return {
    id: run.id,
    startedAt: run.startedAt,
    endedAt,
    distanceM: run.distanceM,
    distanceKm,
    durationSeconds,
    avgPaceSecPerKm,
    // Rough MET-based estimate. Any calorie figure is an estimate and the UI
    // should present it as one — see docs/05 §6 on health claims.
    caloriesBurned: Math.round(distanceKm * 62),
    routePolyline: points.length > 1 ? encodePolyline(points) : null,
    hasAccuracyWarning,
  };
}

export async function discardRun(id: string): Promise<void> {
  const database = await getDb();
  await stopUpdates();
  await database.runAsync('DELETE FROM run_points WHERE run_id = ?', id);
  await database.runAsync('DELETE FROM runs WHERE id = ?', id);
}

export async function markSynced(id: string): Promise<void> {
  const database = await getDb();
  await database.runAsync('UPDATE runs SET synced = 1 WHERE id = ?', id);
  // The server has the run and its encoded route: the raw fixes (thousands
  // per long run) are dead weight on the phone from here on.
  await database.runAsync('DELETE FROM run_points WHERE run_id = ?', id);
}

/** Runs recorded locally but not yet accepted by the server. */
export async function getUnsyncedRuns(owner: string): Promise<FinishedRun[]> {
  const database = await getDb();
  const rows = await database.getAllAsync<{
    id: string;
    started_at: number;
    ended_at: number;
    paused_ms: number;
    distance_m: number;
    dropped_points: number;
    total_points: number;
  }>("SELECT * FROM runs WHERE ended_at IS NOT NULL AND synced = 0 AND (owner = '' OR owner = ?)", owner);

  const runs: FinishedRun[] = [];
  for (const r of rows) {
    const durationSeconds = Math.max(
      1,
      Math.round((r.ended_at - r.started_at - r.paused_ms) / 1000),
    );
    const distanceKm = Math.round((r.distance_m / 1000) * 100) / 100;
    // The route goes up with a late sync too, not just a live finish.
    const points = await database.getAllAsync<{ lat: number; lng: number }>(
      'SELECT lat, lng FROM run_points WHERE run_id = ? ORDER BY t ASC',
      r.id,
    );
    runs.push({
      id: r.id,
      startedAt: r.started_at,
      endedAt: r.ended_at,
      distanceM: r.distance_m,
      distanceKm,
      durationSeconds,
      avgPaceSecPerKm: distanceKm > 0 ? Math.round(durationSeconds / distanceKm) : 0,
      caloriesBurned: Math.round(distanceKm * 62),
      routePolyline: points.length > 1 ? encodePolyline(points) : null,
      hasAccuracyWarning: r.total_points > 0 && r.dropped_points / r.total_points > 0.25,
    });
  }
  return runs;
}

/** Google encoded polyline, so routes cost ~10 bytes a point instead of ~40. */
function encodePolyline(points: { lat: number; lng: number }[]): string {
  let lastLat = 0;
  let lastLng = 0;
  let out = '';

  const encode = (value: number) => {
    let v = value < 0 ? ~(value << 1) : value << 1;
    let chunk = '';
    while (v >= 0x20) {
      chunk += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
      v >>= 5;
    }
    chunk += String.fromCharCode(v + 63);
    return chunk;
  };

  for (const p of points) {
    const lat = Math.round(p.lat * 1e5);
    const lng = Math.round(p.lng * 1e5);
    out += encode(lat - lastLat) + encode(lng - lastLng);
    lastLat = lat;
    lastLng = lng;
  }
  return out;
}
