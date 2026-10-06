import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  Alert,
  AppState,
  Linking,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { StatusBar } from 'expo-status-bar';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { RunHistoryResponse, RunRecord, RunTrackerState, UploadRunRequest } from '@th/types';
import { useRunHistory, useSession, useUploadRun } from '@/api/hooks';
import {
  type ActiveRun,
  type FinishedRun,
  discardRun,
  ensurePermission,
  finishRun,
  getActiveRunFor,
  getUnsyncedRuns,
  recoverActiveRun,
  markSynced,
  movingSeconds,
  pauseRun,
  resumeRun,
  startRun,
} from '@/lib/runTracker';
import { FONTS, colors, layout, radii } from '@/theme';
import { formatDuration, formatTimeOfDay } from '@/lib/time';
import { TagPill } from './TagPill';

/**
 * Run tracker UI — PRD §8.6. Free for everyone, no entitlement check.
 * States: READY → RUNNING ⇄ PAUSED → SUMMARY.
 *
 * Two surfaces, as in the design:
 *  - READY is the Marathon tab's "Run Tracker" sub-tab (MarathonScreenKt
 *    2175-2350): a dark console card, four mini stat tiles, recent runs.
 *  - RUNNING / PAUSED / SUMMARY is RunTrackerScreenKt: a full-screen carbon950
 *    tracker. The design shows it as its own screen whose Close returns to the
 *    tabs while the run keeps recording (MainActivityKt → MAIN_TABS), so here
 *    it is a full-screen modal over whichever screen started it, and Close
 *    only minimises it — the GPS task keeps going and the console card turns
 *    into a "return to run" card.
 */

/** Below this a finished run is treated as an accidental start and not saved. */
const MIN_SAVED_M = 10;

/** #7: the profile's units setting drives every distance and pace shown here. */
const KM_PER_MILE = 1.609344;

/** The design sets every run number in FontFamily.Monospace. */
const MONO = Platform.select({ ios: 'Menlo', android: 'monospace', default: 'monospace' });

/** Recent runs shown before "View all" (#9) — a long history stays one tap away. */
const HISTORY_PREVIEW = 5;

interface Units {
  imperial: boolean;
  /** "km" | "mi" */
  short: string;
  /** "KILOMETRES" | "MILES" */
  long: string;
  /** Converts a stored km figure into the display unit. */
  dist: (km: number) => number;
  /** 6'04" — pace per display unit, from seconds per km. */
  pace: (secPerKm: number) => string;
}

function unitsFor(imperial: boolean): Units {
  return {
    imperial,
    short: imperial ? 'mi' : 'km',
    long: imperial ? 'MILES' : 'KILOMETRES',
    dist: (km) => (imperial ? km / KM_PER_MILE : km),
    pace: (secPerKm) => {
      const sec = imperial ? secPerKm * KM_PER_MILE : secPerKm;
      if (!Number.isFinite(sec) || sec <= 0) return `--'--"`;
      // Round the total first, as lib/time does: 13:59.6 must not read 13'60".
      const total = Math.round(sec);
      return `${Math.floor(total / 60)}'${String(total % 60).padStart(2, '0')}"`;
    },
  };
}

function toUpload(r: FinishedRun): UploadRunRequest {
  return {
    id: r.id,
    startedAt: new Date(r.startedAt).toISOString(),
    endedAt: new Date(r.endedAt).toISOString(),
    distanceKm: r.distanceKm,
    durationSeconds: r.durationSeconds,
    avgPaceSecPerKm: r.avgPaceSecPerKm,
    caloriesBurned: r.caloriesBurned,
    routePolyline: r.routePolyline,
    hasAccuracyWarning: r.hasAccuracyWarning,
  };
}

export function RunTrackerPanel() {
  const [state, setState] = useState<RunTrackerState>('READY');
  const [run, setRun] = useState<ActiveRun | null>(null);
  const [summary, setSummary] = useState<FinishedRun | null>(null);
  const [tooShort, setTooShort] = useState(false);
  const [permission, setPermission] = useState<'granted' | 'denied' | 'blocked' | 'unknown'>('unknown');
  const [elapsed, setElapsed] = useState(0);
  // The full-screen tracker is open; closing it never stops the run.
  const [liveOpen, setLiveOpen] = useState(false);
  const [showAll, setShowAll] = useState(false);
  const poll = useRef<ReturnType<typeof setInterval> | null>(null);
  /** True while this panel's own Finish is writing — the poll must not race it. */
  const finishing = useRef(false);

  const { data: history } = useRunHistory();
  const upload = useUploadRun();
  // Runs on this phone belong to whoever recorded them (shared phones).
  const { data: session } = useSession();
  const owner = session?.profile.id ?? '';
  const u = unitsFor(session?.profile.units === 'IMPERIAL');

  // §8.6: "App killed mid-run → run must be recoverable, not lost."
  // On mount we look for an unfinished run in SQLite and resume the UI on it.
  useEffect(() => {
    void (async () => {
      if (!owner) return;
      // Finds an unfinished run — and if the app was killed mid-run, parks it
      // as paused (GPS stopped with the process) rather than faking "live".
      const active = await recoverActiveRun(owner);
      if (active) {
        setRun(active);
        // Recovered paused runs show their real time too, not 00:00.
        setElapsed(movingSeconds(active));
        setState(active.state === 'PAUSED' ? 'PAUSED' : 'RUNNING');
        setLiveOpen(true);
      }
      // Flush anything recorded while offline — one at a time, each marked
      // synced as it lands. (mutate() in a loop keeps only the LAST call's
      // onSuccess, so earlier runs were re-uploaded forever and their GPS
      // points never pruned.) Uploads are idempotent on the run id.
      const pending = await getUnsyncedRuns(owner);
      for (const r of pending) {
        try {
          await upload.mutateAsync(toUpload(r));
          await markSynced(r.id);
        } catch {
          break; // offline again — the rest go next time
        }
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [owner]);

  // While running, re-read the SQLite row so the UI reflects what the
  // background task has written — including points captured with the app
  // backgrounded or the screen off.
  useEffect(() => {
    if (state !== 'RUNNING' && state !== 'PAUSED') {
      if (poll.current) clearInterval(poll.current);
      return;
    }
    poll.current = setInterval(() => {
      void (async () => {
        const active = await getActiveRunFor(owner);
        // This panel's own Finish is mid-flight: its summary is coming.
        if (finishing.current) return;
        if (active && active.id === run?.id) {
          setRun(active);
          // Moving time: the clock doesn't jump forward by the pause on resume.
          setElapsed(movingSeconds(active));
        } else if (!active || (run && active.id !== run.id)) {
          // Finished or discarded from the other tracker screen (Home tile vs
          // Marathon tab): don't sit on a stale "live" view of it.
          backToReady();
        }
      })();
    }, state === 'RUNNING' ? 1000 : 3000);
    return () => {
      if (poll.current) clearInterval(poll.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, owner, run?.id]);

  // Re-sync immediately on foreground, rather than waiting for the next tick.
  // Coming back from the OS settings page is also when location may have been
  // granted: drop the "denied" card so Start asks the OS again instead of
  // leaving a stale "Open Settings" on screen.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      if (s !== 'active') return;
      void getActiveRunFor(owner).then((a) => {
        if (a) setRun(a);
      });
      setPermission((p) => (p === 'granted' ? p : 'unknown'));
    });
    return () => sub.remove();
  }, [owner]);

  // `owner` is a dependency: a start captured before the session loaded would
  // file the run under nobody ('') and show it to anyone on the phone.
  const handleStart = useCallback(async () => {
    const outcome = await ensurePermission();
    setPermission(outcome);
    if (outcome !== 'granted') return;
    // startRun hands back an unfinished run instead of starting a second one,
    // so take its real state and time rather than assuming a fresh start.
    const active = await startRun(owner);
    setRun(active);
    setElapsed(movingSeconds(active));
    setState(active.state === 'PAUSED' ? 'PAUSED' : 'RUNNING');
    setLiveOpen(true);
  }, [owner]);

  const handleFinish = useCallback(async () => {
    // Stays set until the panel returns to Ready: the poll stops in SUMMARY
    // anyway, and resetting it any earlier could let one stale tick through.
    finishing.current = true;
    const result = await finishRun();
    if (!result) {
      // Already finished elsewhere — nothing to save here.
      backToReady();
      return;
    }
    // An accidental start-stop (under 10 m) is not a run: say so and drop it,
    // rather than filling history with "0.00 km" entries.
    if (result.distanceM < MIN_SAVED_M) {
      await discardRun(result.id);
      setTooShort(true);
      setSummary(result);
      setState('SUMMARY');
      return;
    }
    setTooShort(false);
    setSummary(result);
    setState('SUMMARY');
    try {
      await upload.mutateAsync(toUpload(result));
      await markSynced(result.id);
    } catch {
      // Kept on the phone; flushed on the next visit.
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [upload]);

  const backToReady = () => {
    finishing.current = false;
    setSummary(null);
    setTooShort(false);
    setRun(null);
    setState('READY');
    setLiveOpen(false);
  };

  /**
   * The design's PAUSED state has only Resume and Finish (RunTrackerScreenKt
   * :720-743), so Discard lives in the confirmation Finish opens. A finished
   * run can't be resumed, and deleting one can't be undone — neither should
   * hang on one stray tap of an 80dp button.
   */
  const confirmFinish = () => {
    Alert.alert('Finish your run?', 'Save it to your run history, or discard it if you started by mistake.', [
      {
        text: 'Discard',
        style: 'destructive',
        onPress: () => {
          if (!run) return;
          void (async () => {
            // Only the run that is still live — never one already finished
            // (and maybe not yet synced) from the other tracker screen.
            const current = await getActiveRunFor(owner);
            if (current?.id === run.id) await discardRun(run.id);
            backToReady();
          })();
        },
      },
      { text: 'Keep going', style: 'cancel' },
      { text: 'Save run', onPress: () => void handleFinish() },
    ]);
  };

  const live = state === 'RUNNING' || state === 'PAUSED';
  // Android back: minimise a live run (it keeps recording); from the summary
  // it is the same as "Done".
  const onRequestClose = () => (live ? setLiveOpen(false) : backToReady());

  return (
    <>
      <ReadyView
        u={u}
        history={history}
        showAll={showAll}
        onToggleAll={() => setShowAll((v) => !v)}
        console={
          live ? (
            <ConsoleCard
              u={u}
              pill={state === 'PAUSED' ? 'PAUSED' : 'LIVE RECORDING'}
              live={state === 'RUNNING'}
              distanceKm={(run?.distanceM ?? 0) / 1000}
              action="RETURN"
              onAction={() => setLiveOpen(true)}
              caption={
                state === 'PAUSED'
                  ? 'Your run is paused · tap Return to resume or finish'
                  : 'Your run is still recording · tap Return to see it'
              }
            />
          ) : permission === 'denied' || permission === 'blocked' ? (
            <PermissionCard
              blocked={permission === 'blocked'}
              // §8.6: permission denied → explain and route to settings. We
              // never pretend the tracker works without it.
              onPress={() =>
                permission === 'blocked' ? void Linking.openSettings() : void handleStart()
              }
            />
          ) : (
            <ConsoleCard
              u={u}
              pill="GPS TRACKING READY"
              distanceKm={0}
              action="START"
              onAction={() => void handleStart()}
              caption="Full GPS tracking · Records in background with screen off"
            />
          )
        }
      />

      <Modal
        visible={liveOpen && state !== 'READY'}
        animationType="slide"
        statusBarTranslucent
        navigationBarTranslucent
        onRequestClose={onRequestClose}
      >
        <StatusBar style="light" />
        <DarkFrame>
          {state === 'SUMMARY' && summary && tooShort ? (
            <TooShort onBack={backToReady} />
          ) : state === 'SUMMARY' && summary ? (
            <Summary
              u={u}
              summary={summary}
              // Says what actually happened: the run is always safe on the
              // phone, and only "saved to your history" once the server has it.
              status={
                upload.isSuccess
                  ? 'Saved to your TimesHealth+ run history.'
                  : upload.isError
                    ? 'Saved on this phone — it will sync to your run history next time you’re online.'
                    : 'Saved on this phone · syncing to your run history…'
              }
              onDone={backToReady}
            />
          ) : (
            <Live
              u={u}
              state={state}
              run={run}
              elapsed={elapsed}
              onClose={() => setLiveOpen(false)}
              onPause={() => void pauseRun().then(() => setState('PAUSED'))}
              onResume={() => void resumeRun().then(() => setState('RUNNING'))}
              onFinish={confirmFinish}
            />
          )}
        </DarkFrame>
      </Modal>
    </>
  );
}

// ── READY: the Marathon tab sub-tab ─────────────────────────────────────────

function ReadyView({
  u,
  history,
  showAll,
  onToggleAll,
  console: consoleCard,
}: {
  u: Units;
  history: RunHistoryResponse | undefined;
  showAll: boolean;
  onToggleAll: () => void;
  console: ReactNode;
}) {
  const runs = history?.runs ?? [];
  const totals = history?.totals;

  // MarathonScreenKt:2308-2311. Every figure comes from the server's
  // aggregate over ALL runs (#9) — the list itself stops at the latest 100.
  const stats = useMemo(() => {
    const lifetimePace =
      totals && totals.distanceKm > 0 ? totals.durationSeconds / totals.distanceKm : 0;
    return {
      monthKm: totals?.monthDistanceKm ?? 0,
      longestKm: totals?.longestKm ?? 0,
      lifetimePace,
    };
  }, [totals]);

  const visible = showAll ? runs : runs.slice(0, HISTORY_PREVIEW);
  const hasMore = runs.length > HISTORY_PREVIEW;

  return (
    <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
      <View style={styles.consoleWrap}>{consoleCard}</View>

      <View style={styles.statRow}>
        <MiniStat value={u.dist(stats.monthKm).toFixed(1)} label={`${u.short} this\nmonth`} />
        <MiniStat value={String(totals?.runs ?? 0)} label={'runs\nlogged'} />
        <MiniStat value={u.pace(stats.lifetimePace)} label={`avg pace\n/${u.short}`} />
        <MiniStat value={u.dist(stats.longestKm).toFixed(1)} label={`longest\nrun (${u.short})`} />
      </View>

      <View style={{ height: 18 }} />
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Recent logged runs</Text>
        {hasMore ? (
          <Pressable onPress={onToggleAll} hitSlop={8} accessibilityRole="button">
            <Text style={styles.sectionAction}>
              {showAll ? 'Show fewer' : `View all (${runs.length})`}
            </Text>
          </Pressable>
        ) : null}
      </View>

      {history && runs.length === 0 ? (
        <Text style={styles.emptyNote}>Your runs will appear here once you finish your first one.</Text>
      ) : null}

      {visible.map((r) => (
        <RunRow key={r.id} run={r} u={u} />
      ))}

      {totals && totals.runs > 0 ? (
        <Text style={styles.lifetime}>
          {/* The totals cover every run, not just the list above. */}
          Lifetime · {totals.runs} {totals.runs === 1 ? 'run' : 'runs'} ·{' '}
          {u.dist(totals.distanceKm).toFixed(1)} {u.short} · {formatDuration(totals.durationSeconds)}
          {showAll && totals.runs > runs.length ? `\nShowing your latest ${runs.length} runs.` : ''}
        </Text>
      ) : null}
    </ScrollView>
  );
}

/** MarathonScreenKt:2175-2253 — the dark console card. */
function ConsoleCard({
  u,
  pill,
  live,
  distanceKm,
  action,
  onAction,
  caption,
}: {
  u: Units;
  pill: string;
  live?: boolean;
  distanceKm: number;
  action: string;
  onAction: () => void;
  caption: string;
}) {
  return (
    <View style={styles.console}>
      <TagPill label={pill} tone={live ? 'LIVE' : 'EMERALD'} style={styles.selfCenter} />
      <View style={{ height: 14 }} />
      <Text style={styles.consoleNumber}>{u.dist(distanceKm).toFixed(2)}</Text>
      <Text style={styles.consoleUnit}>{u.long}</Text>
      <View style={{ height: 24 }} />
      <Pressable
        style={({ pressed }) => [styles.startBtn, pressed && styles.pressed]}
        onPress={onAction}
        accessibilityRole="button"
        accessibilityLabel={action === 'START' ? 'Start run' : 'Return to your run'}
      >
        <Text style={styles.startText}>{action}</Text>
      </Pressable>
      <View style={{ height: 16 }} />
      <Text style={styles.consoleCaption}>{caption}</Text>
    </View>
  );
}

/** §8.6 permission denied/blocked, in the console card's place. */
function PermissionCard({ blocked, onPress }: { blocked: boolean; onPress: () => void }) {
  return (
    <View style={styles.console}>
      <TagPill label="Location off" tone="GOLD" style={styles.selfCenter} />
      <View style={{ height: 14 }} />
      <MaterialIcons name="location-off" size={40} color="rgba(255,255,255,0.6)" />
      <Text style={styles.permTitle}>Location is needed to track runs</Text>
      <Text style={styles.consoleCaption}>
        TimesHealth+ uses GPS to measure your distance, pace and route. Without it the tracker
        cannot work.
      </Text>
      <View style={{ height: 18 }} />
      <Pressable style={styles.permBtn} onPress={onPress} accessibilityRole="button">
        <Text style={styles.permBtnText}>{blocked ? 'Open Settings' : 'Allow location'}</Text>
      </Pressable>
    </View>
  );
}

/** MarathonScreenKt MiniStatBlock: R12, paperWhite, borderRule, padding 10. */
function MiniStat({ value, label }: { value: string; label: string }) {
  return (
    <View style={styles.miniStat}>
      <Text style={styles.miniValue} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
      <Text style={styles.miniLabel}>{label}</Text>
    </View>
  );
}

/** MarathonScreenKt RunRecordRowCard:4086-4131. */
function RunRow({ run: r, u }: { run: RunRecord; u: Units }) {
  return (
    <View style={styles.runRowWrap}>
      <View style={styles.runRow}>
        <View style={{ flex: 1, gap: 2 }}>
          <View style={styles.runKmRow}>
            <Text style={styles.runKm}>
              {u.dist(r.distanceKm).toFixed(2)} {u.short}
            </Text>
            {r.hasAccuracyWarning ? (
              // §8.6 poor GPS: this distance may be short — say so in history too.
              <MaterialIcons
                name="warning-amber"
                size={16}
                color={colors.amberWarn}
                accessibilityLabel="Weak GPS on this run"
              />
            ) : null}
          </View>
          <Text style={styles.runMeta} numberOfLines={1}>
            {runDate(r.startedAt)} · {u.pace(r.avgPaceSecPerKm)} /{u.short}
          </Text>
        </View>
        {/* Calories are an estimate (docs/05 §6) — the tilde says so. */}
        <TagPill label={`~${r.caloriesBurned} kcal`} tone="NEUTRAL" />
      </View>
    </View>
  );
}

/** "Today · 6:15 am", "Yesterday · …", else "Sat, 3 Oct · …" (design: dateStr). */
function runDate(iso: string): string {
  const d = new Date(iso);
  const midnight = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((midnight(new Date()) - midnight(d)) / 86_400_000);
  const day =
    days === 0
      ? 'Today'
      : days === 1
        ? 'Yesterday'
        : d.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
  return `${day} · ${formatTimeOfDay(iso)}`;
}

// ── RUNNING / PAUSED / SUMMARY: RunTrackerScreenKt ──────────────────────────

/** fillMaxSize, carbon950, status + navigation bar insets, padding 20. */
function DarkFrame({ children }: { children: ReactNode }) {
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.dark, { paddingTop: insets.top + 20, paddingBottom: insets.bottom + 20 }]}>
      {children}
    </View>
  );
}

function Live({
  u,
  state,
  run,
  elapsed,
  onClose,
  onPause,
  onResume,
  onFinish,
}: {
  u: Units;
  state: RunTrackerState;
  run: ActiveRun | null;
  elapsed: number;
  onClose: () => void;
  onPause: () => void;
  onResume: () => void;
  onFinish: () => void;
}) {
  const km = (run?.distanceM ?? 0) / 1000;
  const pace = km > 0 ? elapsed / km : 0;
  // No GPS is read while paused, so a signal warning means nothing then.
  const weakSignal =
    state === 'RUNNING' &&
    (run?.totalPoints ?? 0) > 8 &&
    (run?.droppedPoints ?? 0) / (run?.totalPoints ?? 1) > 0.25;

  return (
    <View style={styles.liveCol}>
      {/* Row: Close · pill · 24dp spacer, SpaceBetween (:437-450). */}
      <View style={styles.topRow}>
        <Pressable
          onPress={onClose}
          style={styles.iconBtn}
          accessibilityRole="button"
          accessibilityLabel="Close tracker. Your run keeps recording."
        >
          <MaterialIcons name="close" size={24} color={colors.paperWhite} />
        </Pressable>
        {weakSignal ? (
          <TagPill label="Weak GPS signal" tone="GOLD" />
        ) : (
          <TagPill
            label={state === 'PAUSED' ? 'PAUSED' : 'LIVE RECORDING'}
            tone={state === 'RUNNING' ? 'LIVE' : 'EMERALD'}
          />
        )}
        <View style={{ width: 24, height: 24 }} />
      </View>

      <View style={styles.liveStats}>
        <Text style={styles.liveNumber}>{u.dist(km).toFixed(2)}</Text>
        <Text style={styles.liveUnit}>{u.long}</Text>
        <View style={{ height: 36 }} />
        <View style={styles.liveRow}>
          <View style={styles.liveMetric}>
            <Text style={styles.liveValue}>{formatDuration(elapsed)}</Text>
            <Text style={styles.liveLabel}>TIME</Text>
          </View>
          <View style={styles.liveMetric}>
            <Text style={styles.liveValue}>
              {u.pace(pace)}
              <Text style={styles.liveValueUnit}> /{u.short}</Text>
            </Text>
            <Text style={styles.liveLabel}>CURRENT PACE</Text>
          </View>
        </View>
      </View>

      <View style={styles.controls}>
        {state === 'RUNNING' ? (
          <Pressable
            style={({ pressed }) => [styles.circle, styles.pauseBtn, pressed && styles.pressed]}
            onPress={onPause}
            accessibilityRole="button"
            accessibilityLabel="Pause run"
          >
            <MaterialIcons name="pause" size={36} color={colors.carbon950} />
          </Pressable>
        ) : (
          <View style={styles.pausedControls}>
            <Pressable
              style={({ pressed }) => [styles.circle, styles.resumeBtn, pressed && styles.pressed]}
              onPress={onResume}
              accessibilityRole="button"
              accessibilityLabel="Resume run"
            >
              <MaterialIcons name="play-arrow" size={36} color={colors.paperWhite} />
            </Pressable>
            <Pressable
              style={({ pressed }) => [styles.circle, styles.finishBtn, pressed && styles.pressed]}
              onPress={onFinish}
              accessibilityRole="button"
              accessibilityLabel="Finish run"
            >
              <MaterialIcons name="stop" size={36} color={colors.paperWhite} />
            </Pressable>
          </View>
        )}
      </View>
    </View>
  );
}

function Summary({
  u,
  summary,
  status,
  onDone,
}: {
  u: Units;
  summary: FinishedRun;
  status: string;
  onDone: () => void;
}) {
  return (
    <View style={styles.summaryCol}>
      <ScrollView contentContainerStyle={styles.summaryTop} showsVerticalScrollIndicator={false}>
        <View style={{ height: 20 }} />
        <View style={styles.badge}>
          <Text style={styles.badgeEmoji}>🏃</Text>
        </View>
        <View style={{ height: 14 }} />
        <Text style={styles.summaryTitle}>Run Complete!</Text>
        <Text style={styles.summarySub}>{status}</Text>

        {summary.hasAccuracyWarning ? (
          <View style={styles.warnRow}>
            <MaterialIcons name="warning-amber" size={16} color={colors.amberWarn} />
            <Text style={styles.warnText}>
              GPS signal was weak for part of this run, so the distance may be lower than you
              actually covered.
            </Text>
          </View>
        ) : null}

        <View style={{ height: 30 }} />
        <View style={styles.summaryCard}>
          <Text style={[styles.summaryLabel, { letterSpacing: 1 }]}>DISTANCE</Text>
          <Text style={styles.summaryDistance}>
            {u.dist(summary.distanceKm).toFixed(2)} {u.short}
          </Text>
          <View style={{ height: 18 }} />
          <View style={{ flexDirection: 'row' }}>
            <View style={{ flex: 1 }}>
              <Text style={styles.summaryLabel}>DURATION</Text>
              <Text style={styles.summaryValue}>{formatDuration(summary.durationSeconds)}</Text>
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.summaryLabel}>AVG PACE</Text>
              <Text style={styles.summaryValue}>
                {u.pace(summary.avgPaceSecPerKm)}
                <Text style={styles.summaryValueUnit}> /{u.short}</Text>
              </Text>
            </View>
          </View>
        </View>
      </ScrollView>

      <Pressable
        style={({ pressed }) => [styles.doneBtn, pressed && styles.pressed]}
        onPress={onDone}
        accessibilityRole="button"
      >
        <Text style={styles.doneText}>Done — View History</Text>
      </Pressable>
    </View>
  );
}

function TooShort({ onBack }: { onBack: () => void }) {
  return (
    <View style={styles.summaryCol}>
      <View style={[styles.summaryTop, { flex: 1, justifyContent: 'center' }]}>
        <View style={[styles.badge, { backgroundColor: colors.carbon800 }]}>
          <MaterialIcons name="directions-walk" size={32} color={colors.paperWhite} />
        </View>
        <View style={{ height: 14 }} />
        <Text style={styles.summaryTitle}>Too short to save</Text>
        <Text style={styles.summarySub}>
          This run covered less than 10 metres, so it wasn&rsquo;t added to your history.
        </Text>
      </View>
      <Pressable
        style={({ pressed }) => [styles.doneBtn, pressed && styles.pressed]}
        onPress={onBack}
        accessibilityRole="button"
      >
        <Text style={styles.doneText}>Back</Text>
      </Pressable>
    </View>
  );
}

const WHITE_60 = 'rgba(255,255,255,0.6)';
const WHITE_70 = 'rgba(255,255,255,0.7)';

const styles = StyleSheet.create({
  // Design list: contentPadding(bottom = 90); rows carry the 18dp gutter.
  scroll: { paddingTop: 8, paddingBottom: 90 },
  pressed: { opacity: 0.85 },
  selfCenter: { alignSelf: 'center' },

  // padding(18, 8) → Card carbon950, R22, padding 24, centred.
  consoleWrap: { paddingHorizontal: layout.screenGutter, paddingVertical: 8 },
  console: {
    backgroundColor: colors.carbon950,
    borderRadius: radii.hero,
    padding: 24,
    alignItems: 'center',
  },
  consoleNumber: {
    fontFamily: MONO,
    fontSize: 64,
    lineHeight: 64,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  consoleUnit: {
    fontFamily: FONTS.sans,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 1,
    color: WHITE_60,
  },
  startBtn: {
    width: 100,
    height: 100,
    borderRadius: 50,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  startText: { fontFamily: FONTS.serif, fontSize: 18, fontWeight: '700', color: colors.paperWhite },
  consoleCaption: {
    fontFamily: FONTS.sans,
    fontSize: 11,
    lineHeight: 15,
    color: WHITE_60,
    textAlign: 'center',
  },
  permTitle: {
    fontFamily: FONTS.serif,
    fontSize: 18,
    fontWeight: '700',
    color: colors.paperWhite,
    textAlign: 'center',
    marginTop: 10,
    marginBottom: 6,
  },
  permBtn: {
    height: 46,
    alignSelf: 'stretch',
    borderRadius: radii.md,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  permBtnText: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.paperWhite },

  // Spacer 16, then Row(spacedBy 8) of four weight(1) MiniStatBlocks.
  statRow: {
    flexDirection: 'row',
    gap: 8,
    paddingHorizontal: layout.screenGutter,
    marginTop: 16,
  },
  miniStat: {
    flex: 1,
    backgroundColor: colors.paperWhite,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    padding: 10,
    alignItems: 'center',
  },
  miniValue: {
    fontFamily: FONTS.serif,
    fontSize: 19,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  miniLabel: {
    fontFamily: FONTS.sans,
    fontSize: 9,
    lineHeight: 11,
    color: colors.textMuted,
    textAlign: 'center',
  },

  // CommonComponentsKt.SectionHeader: serif 18 SemiBold, padding(18, 12),
  // action CoralBrand 12 Bold.
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    paddingHorizontal: layout.screenGutter,
    paddingVertical: 12,
  },
  sectionTitle: {
    flex: 1,
    fontFamily: FONTS.serif,
    fontSize: 18,
    lineHeight: 22,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  sectionAction: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.coralBrand },
  emptyNote: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    color: colors.textMuted,
    paddingHorizontal: layout.screenGutter,
  },
  lifetime: {
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    lineHeight: 16,
    color: colors.textMuted,
    textAlign: 'center',
    paddingHorizontal: layout.screenGutter,
    marginTop: 12,
  },

  // RunRecordRowCard: wrapper padding(18, 4); card R14, paperWhite,
  // borderRule, padding 14, SpaceBetween.
  runRowWrap: { paddingHorizontal: layout.screenGutter, paddingVertical: 4 },
  runRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    backgroundColor: colors.paperWhite,
    borderRadius: radii.option,
    borderWidth: 1,
    borderColor: colors.borderRule,
    padding: 14,
  },
  runKmRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  runKm: { fontFamily: MONO, fontSize: 18, fontWeight: '700', color: colors.textPrimary },
  runMeta: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },

  // ── Dark tracker ──
  dark: { flex: 1, backgroundColor: colors.carbon950, paddingHorizontal: 20 },
  liveCol: { flex: 1, justifyContent: 'space-between' },
  topRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  iconBtn: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  liveStats: { alignItems: 'center' },
  liveNumber: {
    fontFamily: MONO,
    fontSize: 80,
    lineHeight: 80,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  liveUnit: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    fontWeight: '800',
    letterSpacing: 1,
    color: WHITE_60,
  },
  liveRow: { flexDirection: 'row', justifyContent: 'space-around', alignSelf: 'stretch' },
  liveMetric: { alignItems: 'center' },
  liveValue: { fontFamily: MONO, fontSize: 34, fontWeight: '700', color: colors.paperWhite },
  liveValueUnit: { fontFamily: MONO, fontSize: 14, fontWeight: '700', color: WHITE_60 },
  liveLabel: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '700', color: colors.textMuted },
  controls: { alignItems: 'center', justifyContent: 'center', paddingBottom: 20 },
  pausedControls: { flexDirection: 'row', alignItems: 'center', gap: 24 },
  circle: { alignItems: 'center', justifyContent: 'center' },
  // RUNNING: one 90dp AmberWarn Pause (36, carbon950).
  pauseBtn: { width: 90, height: 90, borderRadius: 45, backgroundColor: colors.amberWarn },
  // PAUSED: 80dp LiveEmerald Resume and 80dp CrimsonAlert Finish.
  resumeBtn: { width: 80, height: 80, borderRadius: 40, backgroundColor: colors.liveEmerald },
  finishBtn: { width: 80, height: 80, borderRadius: 40, backgroundColor: colors.crimsonAlert },

  summaryCol: { flex: 1, justifyContent: 'space-between', gap: 16 },
  summaryTop: { alignItems: 'center' },
  badge: {
    width: 70,
    height: 70,
    borderRadius: 35,
    backgroundColor: colors.liveEmeraldTint,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeEmoji: { fontSize: 32 },
  summaryTitle: {
    fontFamily: FONTS.serif,
    fontSize: 32,
    lineHeight: 38,
    fontWeight: '700',
    color: colors.paperWhite,
    textAlign: 'center',
  },
  summarySub: {
    fontFamily: FONTS.sans,
    fontSize: 13,
    lineHeight: 18,
    color: WHITE_70,
    textAlign: 'center',
  },
  warnRow: {
    flexDirection: 'row',
    gap: 8,
    alignItems: 'flex-start',
    marginTop: 12,
    paddingHorizontal: 4,
  },
  warnText: { flex: 1, fontFamily: FONTS.sans, fontSize: 12, lineHeight: 17, color: WHITE_70 },
  // Card carbon900, R20, padding 20.
  summaryCard: {
    alignSelf: 'stretch',
    backgroundColor: colors.carbon900,
    borderRadius: radii.xl,
    padding: 20,
  },
  summaryLabel: { fontFamily: FONTS.sans, fontSize: 10, fontWeight: '700', color: colors.textMuted },
  summaryDistance: { fontFamily: MONO, fontSize: 48, fontWeight: '700', color: colors.paperWhite },
  summaryValue: { fontFamily: MONO, fontSize: 24, fontWeight: '700', color: colors.paperWhite },
  summaryValueUnit: { fontFamily: MONO, fontSize: 13, fontWeight: '700', color: WHITE_60 },
  // Button h52, R14, CoralBrand; "Done — View History" 14 Bold.
  doneBtn: {
    height: 52,
    borderRadius: radii.option,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  doneText: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '700', color: colors.paperWhite },
});
