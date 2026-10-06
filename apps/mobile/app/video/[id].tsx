import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
  type GestureResponderEvent,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { StatusBar } from 'expo-status-bar';
import { useVideoPlayer, VideoView, type SubtitleTrack } from 'expo-video';
import { useEvent, useEventListener } from 'expo';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useQueryClient } from '@tanstack/react-query';
import { ApiRequestError } from '@/api/client';
import { qk, useMySessions, usePlayback, useSession, useSetCompleted, useYogaCatalog } from '@/api/hooks';
import { ErrorState } from '@/components/QueryState';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors } from '@/theme';

/**
 * In-app video player — PRD §6.3 "plays in-app", drawn to the design's
 * VideoPlayerScreenKt: a rounded plum stage holding the picture and the title,
 * then a control panel — scrubber, time and speed, replay 10 / play / forward
 * 10 — and the leave button.
 *
 * ONE DELIBERATE DEPARTURE FROM THE DESIGN: the prototype's exit button reads
 * "Leave Session · Save Attendance" and writes an attendance mark. PRD §7.1 is
 * explicit that watching a recording does NOT count toward the streak — and the
 * design's own FAQ agrees ("Does watching a recording count? No."). The PRD is
 * the hard requirement, so here the button marks the session complete, which is
 * a separate list that never touches the attendance ledger.
 */

/**
 * `.invalid` is reserved by RFC 2606 and can never resolve. Until real session
 * media is uploaded, signed URLs point there — so in development we substitute
 * a public HLS test stream and label it, rather than showing a broken player.
 */
const DEV_SAMPLE_STREAM = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';

/** VideoPlayerScreenKt: Color(0xFF150A1B) canvas; the stage runs #3B0E4A → #150A1B. */
const CANVAS = '#150A1B';
const STAGE = ['#3B0E4A', CANVAS] as const;

/** The design's speed toggle cycles 1.0× → 1.25× → 1.5× → 0.75×. */
const SPEEDS: readonly number[] = [1, 1.25, 1.5, 0.75];
const speedLabel = (r: number) => `${r === 1 ? '1.0' : r}×`;

/** "%02d:%02d" — minutes keep counting past 59, as in the design. */
const clock = (seconds: number) => {
  const s = Math.max(0, Math.floor(seconds));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
};

function resolvePlayable(url: string | null): { uri: string | null; isSample: boolean } {
  if (!url) return { uri: null, isSample: false };
  try {
    if (new URL(url).hostname.endsWith('.invalid')) {
      return { uri: DEV_SAMPLE_STREAM, isSample: true };
    }
  } catch {
    return { uri: null, isSample: false };
  }
  return { uri: url, isSample: false };
}

export default function VideoPlayerScreen() {
  const router = useRouter();
  const qc = useQueryClient();
  const { id } = useLocalSearchParams<{ id: string }>();
  const { data: catalog, isError: catalogFailed, error: catalogError, refetch: refetchCatalog } = useYogaCatalog();
  const { data: session } = useSession();
  const { data: mine } = useMySessions();
  const setCompleted = useSetCompleted();
  const [captions, setCaptions] = useState(false);

  const item = catalog?.sessions.find((s) => s.id === id);
  // Only a KNOWN non-member is locked out here; while the session is still
  // loading, the playback endpoint decides (403 → paywall, below).
  const locked = item && session ? !item.isFree && !session.persona.hasYoga : false;
  const completed = mine?.completedSessionIds.includes(id ?? '') ?? false;

  // The catalogue's URLs are short-TTL signatures (§E2) that the app caches
  // for minutes, so playing one could hand the CDN an expired signature.
  // Ask for a fresh one when the player opens.
  const playback = usePlayback(id ?? '', Boolean(item) && !locked);

  // Latch the first fresh URL: every refetch re-signs it, and a new source
  // would rebuild the player and restart the class from 0:00. A retry asks
  // for a URL newer than the one that failed.
  const [src, setSrc] = useState<string | null>(null);
  const [freshAfter, setFreshAfter] = useState(0);
  useEffect(() => {
    if (src === null && playback.data && playback.dataUpdatedAt > freshAfter) {
      setSrc(playback.data.playbackUrl);
    }
  }, [src, playback.data, playback.dataUpdatedAt, freshAfter]);
  const retry = () => {
    setFreshAfter(playback.dataUpdatedAt);
    setSrc(null);
    void playback.refetch();
  };

  const httpStatus = playback.error instanceof ApiRequestError ? playback.error.status : null;
  const denied = httpStatus === 403;
  const missing = httpStatus === 404;

  // Never a dead tap (§6.3). If the user is not entitled, route to the
  // paywall rather than a black screen.
  useEffect(() => {
    if (item && locked) {
      router.replace({ pathname: '/paywall', params: { productId: 'yoga_annual' } });
    }
  }, [item, locked, router]);

  // NOT_ENTITLED from the playback endpoint: the membership lapsed after the
  // catalogue loaded. Refresh everything that gates on it, then re-gate.
  useEffect(() => {
    if (!denied) return;
    for (const key of [qk.session, qk.home, qk.yogaCatalog, qk.yogaToday]) {
      void qc.invalidateQueries({ queryKey: key });
    }
    router.replace({ pathname: '/paywall', params: { productId: 'yoga_annual' } });
  }, [denied, qc, router]);

  if (!item) {
    if (catalogFailed && !catalog) {
      return (
        <ErrorState
          dark
          error={catalogError}
          onRetry={() => void refetchCatalog()}
          onBack={() => router.back()}
        />
      );
    }
    if (catalog) {
      return <ErrorState dark message="This session isn’t available any more." onBack={() => router.back()} />;
    }
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.paperWhite} />
      </View>
    );
  }

  const { uri, isSample } = resolvePlayable(src);
  const title = item.title;
  const subtitle = `${item.instructor.name} · The Yoga Institute`;
  const fallbackDuration = item.durationMinutes * 60;

  const leave = (
    <Pressable
      style={styles.leaveBtn}
      onPress={() => {
        // "Mark complete" SETS it — never toggles — so a list that hasn't
        // loaded yet can't un-complete a session that was already done.
        if (!completed) setCompleted.mutate({ id: item.id, on: true });
        router.back();
      }}
      accessibilityRole="button"
    >
      <Text style={styles.leaveText}>
        {completed ? 'Leave Session · Completed ✓' : 'Leave Session · Mark Complete'}
      </Text>
    </Pressable>
  );

  // Until there is something to play, the stage says why and the controls rest.
  let waiting: ReactNode = null;
  if (!uri) {
    if (locked || denied) waiting = <ActivityIndicator color={colors.paperWhite} />;
    else if (missing || src !== null) {
      waiting = <StageMessage icon="videocam-off" text="This session’s video isn’t available yet." />;
    } else if (playback.isError) {
      waiting = (
        <StageMessage icon="cloud-off" text="This video couldn’t load. Check your connection." onRetry={retry} />
      );
    } else waiting = <ActivityIndicator color={colors.paperWhite} />;
  }

  return (
    <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
      <StatusBar style="light" />
      <View style={styles.topBar}>
        <Pressable
          onPress={() => router.back()}
          style={styles.iconBtn}
          hitSlop={4}
          accessibilityRole="button"
          accessibilityLabel="Close"
        >
          <MaterialIcons name="close" size={24} color={colors.paperWhite} />
        </Pressable>
        <View style={styles.topRight}>
          {item.isLive ? (
            <TagPill label={`Live · ${item.todayActiveCount} practicing`} tone="LIVE" />
          ) : (
            <TagPill label={isSample ? 'Sample video' : 'On-demand class'} tone="NEUTRAL" />
          )}
          <Pressable
            onPress={() => setCaptions((c) => !c)}
            style={styles.iconBtn}
            hitSlop={4}
            accessibilityRole="switch"
            accessibilityState={{ checked: captions }}
            accessibilityLabel="Captions"
          >
            <MaterialIcons
              name="subtitles"
              size={24}
              color={captions ? colors.coralBrand : 'rgba(255,255,255,0.7)'}
            />
          </Pressable>
        </View>
      </View>

      {uri ? (
        <Player
          key={uri}
          uri={uri}
          title={title}
          subtitle={subtitle}
          fallbackDuration={fallbackDuration}
          captions={captions}
          leave={leave}
          onRetry={retry}
        />
      ) : (
        <>
          <Stage
            media={<View style={styles.mediaCenter}>{waiting}</View>}
            title={title}
            subtitle={subtitle}
            captionNote={captions ? NO_CAPTIONS : null}
          />
          <ControlPanel
            disabled
            progress={0}
            timeText={`${clock(0)} / ${clock(fallbackDuration)}`}
            speed={1}
            isPlaying={false}
            leave={leave}
          />
        </>
      )}
    </SafeAreaView>
  );
}

const NO_CAPTIONS = 'Captions aren’t available for this class yet.';

function Player({
  uri,
  title,
  subtitle,
  fallbackDuration,
  captions,
  leave,
  onRetry,
}: {
  uri: string;
  title: string;
  subtitle: string;
  fallbackDuration: number;
  captions: boolean;
  leave: ReactNode;
  onRetry: () => void;
}) {
  const player = useVideoPlayer(uri, (p) => {
    p.loop = false;
    p.timeUpdateEventInterval = 0.5;
    p.play();
  });
  const { isPlaying } = useEvent(player, 'playingChange', { isPlaying: player.playing });
  const { status } = useEvent(player, 'statusChange', { status: player.status });
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [tracks, setTracks] = useState<SubtitleTrack[]>([]);
  const [rate, setRate] = useState(1);
  const [drag, setDrag] = useState<number | null>(null);

  useEventListener(player, 'timeUpdate', (e) => setTime(e.currentTime));
  useEventListener(player, 'sourceLoad', (e) => {
    setDuration(e.duration);
    setTracks(e.availableSubtitleTracks);
  });
  useEventListener(player, 'availableSubtitleTracksChange', (e) => setTracks(e.availableSubtitleTracks));

  // Captions are the stream's own subtitle track, drawn by the native view.
  // A class without one says so instead of showing invented text.
  useEffect(() => {
    if (tracks.length) player.subtitleTrack = captions ? (tracks[0] ?? null) : null;
  }, [captions, tracks, player]);

  const total = duration > 0 ? duration : fallbackDuration;
  const progress = drag ?? (total > 0 ? Math.min(1, time / total) : 0);

  const seekTo = (fraction: number) => {
    const t = fraction * total;
    player.currentTime = t;
    setTime(t);
    setDrag(null);
  };
  const toggle = () => {
    if (isPlaying) {
      player.pause();
      return;
    }
    // Finished: play again from the top rather than doing nothing.
    if (total > 0 && time >= total - 0.5) player.currentTime = 0;
    player.play();
  };
  const cycleSpeed = () => {
    const next = SPEEDS[(SPEEDS.indexOf(rate) + 1) % SPEEDS.length] ?? 1;
    player.playbackRate = next;
    setRate(next);
  };

  const media = (
    <>
      <VideoView
        player={player}
        style={StyleSheet.absoluteFill}
        nativeControls={false}
        contentFit="contain"
        allowsPictureInPicture
      />
      {status === 'loading' ? (
        <View style={styles.mediaCenter} pointerEvents="none">
          <ActivityIndicator color={colors.paperWhite} />
        </View>
      ) : null}
      {status === 'error' ? (
        <View style={[styles.mediaCenter, styles.mediaScrim]}>
          <StageMessage icon="cloud-off" text="This video couldn’t load. Check your connection." onRetry={onRetry} />
        </View>
      ) : null}
    </>
  );

  return (
    <>
      <Stage
        media={media}
        title={title}
        subtitle={subtitle}
        captionNote={captions && tracks.length === 0 ? NO_CAPTIONS : null}
      />
      <ControlPanel
        progress={progress}
        timeText={`${clock(progress * total)} / ${clock(total)}`}
        speed={rate}
        isPlaying={isPlaying}
        onScrub={setDrag}
        onSeek={seekTo}
        onSpeed={cycleSpeed}
        onToggle={toggle}
        onBack={() => player.seekBy(-10)}
        onForward={() => player.seekBy(10)}
        leave={leave}
      />
    </>
  );
}

/**
 * The stage: weight(1), 20dp inset, R24, plum gradient. The design centres a
 * placeholder glyph over the title; here the picture takes that place.
 */
function Stage({
  media,
  title,
  subtitle,
  captionNote,
}: {
  media: ReactNode;
  title: string;
  subtitle: string;
  captionNote: string | null;
}) {
  return (
    <View style={styles.stage}>
      <LinearGradient colors={STAGE} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={StyleSheet.absoluteFill} />
      <View style={styles.media}>{media}</View>
      <Text style={styles.stageTitle} numberOfLines={2}>
        {title}
      </Text>
      <Text style={styles.stageSub} numberOfLines={1}>
        {subtitle}
      </Text>
      {captionNote ? (
        <View style={styles.caption}>
          <Text style={styles.captionText}>{captionNote}</Text>
        </View>
      ) : null}
    </View>
  );
}

function StageMessage({
  icon,
  text,
  onRetry,
}: {
  icon: 'videocam-off' | 'cloud-off';
  text: string;
  onRetry?: () => void;
}) {
  return (
    <View style={styles.message}>
      <MaterialIcons name={icon} size={32} color={colors.textOnDarkMuted} />
      <Text style={styles.messageText}>{text}</Text>
      {onRetry ? (
        <Pressable style={styles.retryBtn} onPress={onRetry} accessibilityRole="button">
          <Text style={styles.retryText}>Try again</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

/** Bottom panel, padding(24, 18): scrubber, time · speed, Spacer 14, transport, Spacer 18, leave. */
function ControlPanel({
  progress,
  timeText,
  speed,
  isPlaying,
  disabled = false,
  onScrub,
  onSeek,
  onSpeed,
  onToggle,
  onBack,
  onForward,
  leave,
}: {
  progress: number;
  timeText: string;
  speed: number;
  isPlaying: boolean;
  disabled?: boolean;
  onScrub?: (fraction: number) => void;
  onSeek?: (fraction: number) => void;
  onSpeed?: () => void;
  onToggle?: () => void;
  onBack?: () => void;
  onForward?: () => void;
  leave: ReactNode;
}) {
  return (
    <View style={styles.panel}>
      <View style={disabled && styles.resting}>
        <Scrubber value={progress} disabled={disabled} onChange={onScrub} onCommit={onSeek} />
        <View style={styles.timeRow}>
          <Text style={styles.timeText}>{timeText}</Text>
          <Pressable
            onPress={onSpeed}
            disabled={disabled}
            hitSlop={10}
            accessibilityRole="button"
            accessibilityLabel={`Playback speed ${speedLabel(speed)}`}
          >
            <Text style={styles.speedText}>{speedLabel(speed)}</Text>
          </Pressable>
        </View>
        <View style={styles.transport}>
          <Pressable
            style={styles.skipBtn}
            onPress={onBack}
            disabled={disabled}
            accessibilityRole="button"
            accessibilityLabel="Back 10 seconds"
          >
            <MaterialIcons name="replay-10" size={30} color={colors.paperWhite} />
          </Pressable>
          <Pressable
            style={styles.playBtn}
            onPress={onToggle}
            disabled={disabled}
            accessibilityRole="button"
            accessibilityLabel={isPlaying ? 'Pause' : 'Play'}
          >
            <MaterialIcons name={isPlaying ? 'pause' : 'play-arrow'} size={32} color={colors.carbon950} />
          </Pressable>
          <Pressable
            style={styles.skipBtn}
            onPress={onForward}
            disabled={disabled}
            accessibilityRole="button"
            accessibilityLabel="Forward 10 seconds"
          >
            <MaterialIcons name="forward-10" size={30} color={colors.paperWhite} />
          </Pressable>
        </View>
      </View>
      {leave}
    </View>
  );
}

const THUMB = 20;
const clamp01 = (n: number) => Math.max(0, Math.min(1, n));

/**
 * M3 Slider look — PaperWhite thumb, CoralBrand active track, white 20%
 * inactive track — in a 48dp touch row. Drags preview locally and seek once
 * on release, so the stream isn't asked to seek on every pixel.
 */
function Scrubber({
  value,
  disabled,
  onChange,
  onCommit,
}: {
  value: number;
  disabled: boolean;
  onChange?: (fraction: number) => void;
  onCommit?: (fraction: number) => void;
}) {
  const [width, setWidth] = useState(0);
  const originX = useRef(0);
  const span = Math.max(1, width - THUMB);
  const at = (e: GestureResponderEvent) => clamp01((e.nativeEvent.pageX - originX.current - THUMB / 2) / span);
  const v = clamp01(value);

  return (
    <View
      style={styles.scrub}
      onLayout={(e) => setWidth(e.nativeEvent.layout.width)}
      onStartShouldSetResponder={() => !disabled}
      onMoveShouldSetResponder={() => !disabled}
      onResponderTerminationRequest={() => false}
      onResponderGrant={(e) => {
        originX.current = e.nativeEvent.pageX - e.nativeEvent.locationX;
        onChange?.(at(e));
      }}
      onResponderMove={(e) => onChange?.(at(e))}
      onResponderRelease={(e) => onCommit?.(at(e))}
      onResponderTerminate={(e) => onCommit?.(at(e))}
      accessible
      accessibilityRole="adjustable"
      accessibilityLabel="Seek"
      accessibilityValue={{ min: 0, max: 100, now: Math.round(v * 100) }}
      accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
      onAccessibilityAction={(e) =>
        onCommit?.(clamp01(v + (e.nativeEvent.actionName === 'increment' ? 0.05 : -0.05)))
      }
    >
      <View style={styles.track} pointerEvents="none">
        <View style={[styles.trackOn, { width: v * span }]} />
      </View>
      <View style={[styles.thumb, { left: v * span }]} pointerEvents="none" />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: CANVAS },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: CANVAS },

  // Top row: padding(16, 12); Close left, status pill + captions right (spacedBy 6).
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  topRight: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  iconBtn: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },

  stage: {
    flex: 1,
    marginHorizontal: 20,
    borderRadius: 24,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  media: { width: '100%', aspectRatio: 16 / 9, backgroundColor: 'rgba(0,0,0,0.25)' },
  mediaCenter: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center' },
  mediaScrim: { backgroundColor: 'rgba(0,0,0,0.6)' },
  stageTitle: {
    marginTop: 14,
    paddingHorizontal: 24,
    textAlign: 'center',
    fontFamily: FONTS.serif,
    fontSize: 22,
    lineHeight: 28,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  stageSub: {
    marginTop: 4,
    paddingHorizontal: 24,
    textAlign: 'center',
    fontFamily: FONTS.sans,
    fontSize: 13,
    color: 'rgba(255,255,255,0.75)',
  },
  caption: {
    marginTop: 20,
    marginHorizontal: 24,
    // Color(0xBA000000)
    backgroundColor: 'rgba(0,0,0,0.73)',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  captionText: { fontFamily: FONTS.sans, fontSize: 11.5, textAlign: 'center', color: colors.paperWhite },
  message: { alignItems: 'center', paddingHorizontal: 24, gap: 8 },
  messageText: { fontFamily: FONTS.sans, fontSize: 13, textAlign: 'center', color: colors.textOnDarkMuted },
  retryBtn: {
    marginTop: 4,
    borderRadius: 10,
    paddingHorizontal: 16,
    paddingVertical: 8,
    backgroundColor: 'rgba(255,255,255,0.16)',
  },
  retryText: { fontFamily: FONTS.sans, fontSize: 12.5, fontWeight: '700', color: colors.paperWhite },

  panel: { paddingHorizontal: 24, paddingVertical: 18 },
  resting: { opacity: 0.4 },
  scrub: { height: 48, justifyContent: 'center' },
  track: {
    position: 'absolute',
    left: THUMB / 2,
    right: THUMB / 2,
    height: 4,
    borderRadius: 2,
    backgroundColor: 'rgba(255,255,255,0.2)',
    overflow: 'hidden',
  },
  trackOn: { height: 4, backgroundColor: colors.coralBrand },
  thumb: {
    position: 'absolute',
    top: (48 - THUMB) / 2,
    width: THUMB,
    height: THUMB,
    borderRadius: THUMB / 2,
    backgroundColor: colors.paperWhite,
  },
  timeRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  timeText: { fontFamily: FONTS.sans, fontSize: 11, color: 'rgba(255,255,255,0.6)' },
  speedText: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '700', color: colors.coralBrand },
  transport: {
    marginTop: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 20,
  },
  skipBtn: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  playBtn: {
    width: 62,
    height: 62,
    borderRadius: 31,
    backgroundColor: colors.paperWhite,
    alignItems: 'center',
    justifyContent: 'center',
  },
  leaveBtn: {
    marginTop: 18,
    height: 48,
    borderRadius: 12,
    backgroundColor: 'rgba(255,255,255,0.16)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  leaveText: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.paperWhite },
});
