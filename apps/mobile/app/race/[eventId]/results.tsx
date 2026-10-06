import {
  ActivityIndicator,
  FlatList,
  Image,
  Linking,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useRaceDetail } from '@/api/hooks';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, layout, radii, type } from '@/theme';
import { ErrorState } from '@/components/QueryState';

/**
 * Race results — PRD §8.4, design RaceResultsScreenKt ("Official Results").
 *
 * "Results come from the timing partner feed. If results are not yet published,
 * the box shows a pending state — not an empty results screen." That pending
 * branch is the first thing this screen handles.
 *
 * Design order: carbon chip-time hero → four stat boxes → finisher-medal kit
 * card → "Official Timing Splits" → Download certificate. Race photos are a
 * PRD §8.4 item the design doesn't draw; they sit after the splits.
 */

const GUTTER = layout.screenGutter;
const MONO = Platform.select({ ios: 'Menlo', android: 'monospace', default: 'monospace' });

/** "1:52:21" / "26:40" → seconds. Null when the feed sends something else. */
function toSeconds(time: string | null | undefined): number | null {
  if (!time) return null;
  const parts = time.trim().split(':').map(Number);
  if (parts.length < 2 || parts.length > 3 || parts.some((p) => !Number.isFinite(p))) return null;
  return parts.reduce((acc, p) => acc * 60 + p, 0);
}

/** Race distance in km from the category code; a "21K" is a half marathon. */
function categoryKm(category: string): number | null {
  const km = Number.parseFloat(category);
  if (!Number.isFinite(km) || km <= 0) return null;
  if (km === 21) return 21.0975;
  if (km === 42) return 42.195;
  return km;
}

/** "0 – 5 km" → 5. Null when the label isn't a from–to range. */
function splitKm(label: string): number | null {
  const m = label.match(/([\d.]+)\s*[–-]\s*([\d.]+)/);
  if (!m) return null;
  const km = Number(m[2]) - Number(m[1]);
  return km > 0 ? km : null;
}

/** Design pace format: 5'20" /km. */
function paceText(seconds: number, km: number): string {
  const perKm = Math.round(seconds / km);
  return `${Math.floor(perKm / 60)}'${String(perKm % 60).padStart(2, '0')}" /km`;
}

export default function RaceResultsScreen() {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { eventId } = useLocalSearchParams<{ eventId: string }>();
  const { data, isLoading, isError, error, refetch } = useRaceDetail(eventId ?? '');
  const back = () => router.back();

  if (isError && !data) {
    return (
      <SafeAreaView style={styles.root} edges={['top']}>
        <TopBar onBack={back} />
        <ErrorState error={error} onRetry={() => void refetch()} />
      </SafeAreaView>
    );
  }
  if (isLoading || !data) {
    return (
      <SafeAreaView style={styles.root} edges={['top']}>
        <TopBar onBack={back} />
        <View style={styles.center}>
          <ActivityIndicator color={colors.coralBrand} />
        </View>
      </SafeAreaView>
    );
  }

  const result = data.result;

  if (!result || !result.published) {
    return (
      <SafeAreaView style={styles.root} edges={['top']}>
        <TopBar onBack={back} />
        <View style={styles.pending}>
          <TagPill label="Results pending" tone="GOLD" />
          <Text style={[type.headlineLarge, styles.centerText]}>Results are being published</Text>
          <Text style={[type.bodyMedium, styles.centerText, { color: colors.textSecondary }]}>
            Our timing partner is still verifying chip times for {data.event.name}. We will send you
            a push notification the moment your result is live.
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  const time = result.chipTime ?? result.finishTime;
  const seconds = toSeconds(time);
  const km = categoryKm(result.category);
  // The design's "Speed" box — derived, since the feed only sends pace.
  const speed = seconds && km ? `${(km / (seconds / 3600)).toFixed(1)} km/h` : '—';
  const kit = data.kit;
  const tier = data.event.registration?.tier;

  return (
    <SafeAreaView style={styles.root} edges={['top']}>
      <TopBar onBack={back} />
      <ScrollView
        contentContainerStyle={[styles.scroll, { paddingBottom: 12 + insets.bottom }]}
        showsVerticalScrollIndicator={false}
      >
        {/* $lambda$4: Carbon950, RCS 22, padding 24, centred. */}
        <View style={styles.hero}>
          <TagPill label="Official chip time" tone="GOLD" />
          <Text style={styles.bigTime} adjustsFontSizeToFit numberOfLines={1}>
            {time ?? '—'}
          </Text>
          <Text style={styles.heroMeta}>
            {data.event.name} · {result.category}
          </Text>
        </View>

        <View style={styles.stats}>
          <Stat label="Pace" value={result.avgPace ?? '—'} />
          <Stat label="Speed" value={speed} />
          <Stat label="Overall" value={result.overallRank ? `#${result.overallRank}` : '—'} />
          <Stat label="Age Rank" value={result.ageGroupRank ? `#${result.ageGroupRank}` : '—'} />
        </View>

        {/* §8.4: medal "where available" — design $lambda$10, before the splits. */}
        {result.medalStatus ? (
          <View style={[styles.card, styles.medalCard]}>
            <View style={{ flex: 1 }}>
              <Text style={styles.medalEyebrow}>FINISHER MEDAL</Text>
              <Text style={styles.medalTitle}>
                {kit?.status === 'PICKUP_ONLY'
                  ? 'Collect at the venue'
                  : `${tier === 'PREMIUM' ? 'Premium' : 'Standard'} Kit Delivery`}
              </Text>
              {kit?.trackingRef ? (
                <Text style={styles.medalSub}>
                  {kit.courierName ? `${kit.courierName} tracking` : 'Courier tracking'} #{kit.trackingRef}
                </Text>
              ) : null}
            </View>
            <TagPill label={result.medalStatus} tone="EMERALD" />
          </View>
        ) : null}

        {result.splits.length ? (
          <View style={styles.block18}>
            <Text style={styles.blockTitle}>Official Timing Splits</Text>
            <View style={[styles.card, styles.splitsCard]}>
              {result.splits.map((s) => {
                const secs = toSeconds(s.time);
                const len = splitKm(s.label);
                return (
                  <View key={s.label} style={styles.splitRow}>
                    <Text style={styles.splitLabel}>{s.label}</Text>
                    <View style={styles.splitRight}>
                      <Text style={styles.splitTime}>{s.time}</Text>
                      {secs && len ? <Text style={styles.splitPace}>{paceText(secs, len)}</Text> : null}
                    </View>
                  </View>
                );
              })}
            </View>
          </View>
        ) : null}

        {result.photoUrls.length ? (
          <View style={styles.block18}>
            <Text style={styles.blockTitle}>Race photos</Text>
            <FlatList
              horizontal
              data={result.photoUrls}
              keyExtractor={(u) => u}
              showsHorizontalScrollIndicator={false}
              style={styles.photoRail}
              contentContainerStyle={styles.photoRailContent}
              renderItem={({ item }) => (
                <Pressable onPress={() => void Linking.openURL(item)} accessibilityRole="imagebutton">
                  <Image source={{ uri: item }} style={styles.photo} />
                </Pressable>
              )}
            />
          </View>
        ) : null}

        {result.certificateUrl ? (
          <Pressable
            style={styles.download}
            onPress={() => void Linking.openURL(result.certificateUrl!)}
            accessibilityRole="button"
          >
            <MaterialIcons name="file-download" size={24} color={colors.paperWhite} />
            <Text style={styles.downloadLabel}>Download Finisher Certificate</Text>
          </Pressable>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

/** Design TopAppBar: CanvasBg, ArrowBack, "Official Results" serif 20 Bold. */
function TopBar({ onBack }: { onBack: () => void }) {
  return (
    <View style={styles.topBar}>
      <Pressable
        onPress={onBack}
        style={styles.backBtn}
        hitSlop={4}
        accessibilityRole="button"
        accessibilityLabel="Back"
      >
        <MaterialIcons name="arrow-back" size={24} color={colors.textPrimary} />
      </Pressable>
      <Text style={styles.topTitle} accessibilityRole="header">
        Official Results
      </Text>
    </View>
  );
}

/** ResultStatBox: outlined R12 card, padding 10, centred; 10 Medium label, 14 Bold mono value. */
function Stat({ label, value }: { label: string; value: string }) {
  return (
    <View style={[styles.card, styles.stat]}>
      <Text style={styles.statLabel} numberOfLines={1}>
        {label}
      </Text>
      <Text style={styles.statValue} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  centerText: { textAlign: 'center' },
  // Design LazyColumn contentPadding(18, 12).
  scroll: { paddingHorizontal: GUTTER, paddingTop: 12 },

  topBar: { height: 64, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 4 },
  backBtn: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  topTitle: {
    flex: 1,
    fontFamily: FONTS.serif,
    fontSize: 20,
    fontWeight: '700',
    color: colors.textPrimary,
  },

  pending: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 28,
    gap: 12,
  },

  hero: {
    borderRadius: radii.hero,
    backgroundColor: colors.carbon950,
    padding: 24,
    alignItems: 'center',
  },
  bigTime: {
    marginTop: 10,
    fontFamily: MONO,
    fontSize: 48,
    lineHeight: 52,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  heroMeta: {
    marginTop: 4,
    textAlign: 'center',
    fontFamily: FONTS.sans,
    fontSize: 12.5,
    lineHeight: 17,
    color: 'rgba(255,255,255,0.75)',
  },

  // M3 outlined Card: outlineVariant (borderSubtle) stroke.
  card: {
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
  },
  stats: { marginTop: 14, flexDirection: 'row', gap: 8 },
  stat: { flex: 1, borderRadius: radii.md, padding: 10, alignItems: 'center' },
  statLabel: { fontFamily: FONTS.sans, fontSize: 10, fontWeight: '500', color: colors.textMuted },
  statValue: {
    marginTop: 2,
    fontFamily: MONO,
    fontSize: 14,
    fontWeight: '700',
    color: colors.textPrimary,
  },

  medalCard: {
    marginTop: 14,
    borderRadius: radii.lg,
    padding: 14,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  medalEyebrow: { fontFamily: FONTS.sans, fontSize: 10, fontWeight: '700', color: colors.textMuted },
  medalTitle: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.textPrimary },
  medalSub: { fontFamily: FONTS.sans, fontSize: 11, color: colors.textSecondary },

  block18: { marginTop: 18 },
  blockTitle: {
    fontFamily: FONTS.serif,
    fontSize: 18,
    lineHeight: 23,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  splitsCard: { marginTop: 8, borderRadius: radii.lg, padding: 14 },
  splitRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    paddingVertical: 6,
  },
  splitLabel: { flexShrink: 1, fontFamily: FONTS.sans, fontSize: 12.5, color: colors.textSecondary },
  splitRight: { flexDirection: 'row', alignItems: 'baseline', gap: 16 },
  splitTime: { fontFamily: MONO, fontSize: 13, fontWeight: '700', color: colors.textPrimary },
  splitPace: { fontFamily: MONO, fontSize: 12, color: colors.textMuted },

  // The rail runs edge to edge while its first photo lines up with the gutter.
  photoRail: { marginTop: 8, marginHorizontal: -GUTTER },
  photoRailContent: { paddingHorizontal: GUTTER, gap: 12 },
  photo: { width: 160, height: 120, borderRadius: radii.md, backgroundColor: colors.surfaceSand },

  // Button(h52, RCS 14, CoralBrand) · Download icon + 13.5 Bold, spacedBy 8. Spacer 24 above.
  download: {
    marginTop: 24,
    height: 52,
    borderRadius: radii.option,
    backgroundColor: colors.coralBrand,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  downloadLabel: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.paperWhite },
});
