import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { StatusBar } from 'expo-status-bar';
import { useVideoPlayer, VideoView } from 'expo-video';
import { useEvent } from 'expo';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors } from '@/theme';

/**
 * Instructor reel — PRD §6.3 rail 4, "plays inline". Full screen, dark,
 * autoplaying on loop like any short-form reel; tap anywhere to pause.
 *
 * Opened from Home's reel rail with the clip in the params:
 *   /reel?url=…&title=<instructor name>&handle=<@handle>
 * The URL arrives from navigation, so it is validated before it reaches the
 * player: https only. Anything else gets a plain "not available" screen.
 */

/**
 * `.invalid` is reserved by RFC 2606 and can never resolve. Until real reel
 * media is uploaded, seeded URLs point there — so we substitute a public HLS
 * test stream and label it, rather than showing a broken player. (Same rule
 * as the session player, app/video/[id].tsx.)
 */
const DEV_SAMPLE_STREAM = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';

function resolvePlayable(raw: string | undefined): { uri: string; isSample: boolean } | null {
  if (!raw) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.hostname.endsWith('.invalid')) return { uri: DEV_SAMPLE_STREAM, isSample: true };
  if (!raw.startsWith('https://')) return null;
  return { uri: raw, isSample: false };
}

/** Params can arrive as arrays when a key repeats; only the first counts. */
const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

export default function ReelScreen() {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const params = useLocalSearchParams<{ url?: string; title?: string; handle?: string }>();
  const playable = resolvePlayable(first(params.url));
  const title = first(params.title);
  const handle = first(params.handle);

  return (
    <View style={styles.root}>
      <StatusBar style="light" />

      {playable ? (
        <ReelPlayer uri={playable.uri} />
      ) : (
        <View style={styles.center}>
          <MaterialIcons name="videocam-off" size={32} color={colors.textOnDarkMuted} />
          <Text style={styles.message}>This video isn&rsquo;t available</Text>
        </View>
      )}

      {/* Bottom credit: who made it, over a scrim so it reads on any frame. */}
      {title || handle ? (
        <LinearGradient
          colors={['transparent', 'rgba(0,0,0,0.75)']}
          style={[styles.footer, { paddingBottom: insets.bottom + 24 }]}
          pointerEvents="none"
        >
          <TagPill label={playable?.isSample ? 'Sample video' : 'Reel'} tone="CORAL" />
          {title ? (
            <Text style={styles.name} numberOfLines={1}>
              {title}
            </Text>
          ) : null}
          {handle ? (
            <Text style={styles.handle} numberOfLines={1}>
              {handle}
            </Text>
          ) : null}
        </LinearGradient>
      ) : null}

      <Pressable
        onPress={() => router.back()}
        hitSlop={10}
        style={[styles.close, { top: insets.top + 8 }]}
        accessibilityRole="button"
        accessibilityLabel="Close"
      >
        <MaterialIcons name="close" size={24} color={colors.paperWhite} />
      </Pressable>
    </View>
  );
}

function ReelPlayer({ uri }: { uri: string }) {
  const player = useVideoPlayer(uri, (p) => {
    p.loop = true;
    p.play();
  });
  const { isPlaying } = useEvent(player, 'playingChange', { isPlaying: player.playing });
  const { status } = useEvent(player, 'statusChange', { status: player.status });

  return (
    <Pressable
      style={StyleSheet.absoluteFill}
      onPress={() => (isPlaying ? player.pause() : player.play())}
      accessibilityRole="button"
      accessibilityLabel={isPlaying ? 'Pause' : 'Play'}
    >
      <VideoView
        player={player}
        style={StyleSheet.absoluteFill}
        nativeControls={false}
        // Reels are portrait; fill the screen the way every reel app does.
        contentFit="cover"
      />

      {status === 'loading' ? (
        <View style={styles.center} pointerEvents="none">
          <ActivityIndicator color={colors.paperWhite} />
        </View>
      ) : null}

      {status === 'error' ? (
        <View style={styles.center} pointerEvents="none">
          <MaterialIcons name="cloud-off" size={32} color={colors.textOnDarkMuted} />
          <Text style={styles.message}>This video couldn&rsquo;t load. Check your connection.</Text>
        </View>
      ) : null}

      {/* Paused: a quiet play glyph says "tap to resume". */}
      {!isPlaying && status === 'readyToPlay' ? (
        <View style={styles.center} pointerEvents="none">
          <View style={styles.playBadge}>
            <MaterialIcons name="play-arrow" size={36} color={colors.paperWhite} />
          </View>
        </View>
      ) : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.carbon950 },
  center: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingHorizontal: 32,
  },
  message: {
    fontFamily: FONTS.sans,
    fontSize: 13,
    textAlign: 'center',
    color: colors.textOnDarkMuted,
  },
  playBadge: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: 'rgba(0,0,0,0.4)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  close: {
    position: 'absolute',
    left: 16,
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.35)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  footer: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingHorizontal: 18,
    paddingTop: 48,
    gap: 4,
  },
  name: {
    marginTop: 6,
    fontFamily: FONTS.sans,
    fontSize: 16,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  handle: { fontFamily: FONTS.sans, fontSize: 13, color: 'rgba(255,255,255,0.8)' },
});
