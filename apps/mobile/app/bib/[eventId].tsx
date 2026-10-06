import { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons, MaterialIcons } from '@expo/vector-icons';
import QRCode from 'react-native-qrcode-svg';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useBibToken, useRaceDetail } from '@/api/hooks';
import {
  loadOfflinePass,
  offlinePassGeneration,
  saveOfflinePass,
  type OfflinePass,
} from '@/lib/offlinePass';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, radii } from '@/theme';
import { ErrorState } from '@/components/QueryState';
import { useServerNow } from '@/lib/time';

/**
 * Digital bib + QR — PRD §8.3, design DigitalBibScreenKt ("Digital Bib & Pass").
 *
 * The QR encodes a SIGNED, SHORT-LIVED token, never the bare bib number
 * (docs/04 T1). A screenshot of someone else's pass stops scanning within a
 * minute, and the expo scanner validates server-side.
 *
 * `offlinePayload` is a 7-day signature kept on the device (src/lib/offlinePass),
 * because a stadium gate with 20,000 phones is exactly where connectivity
 * fails. With no signal — even from a cold start — the pass renders from it.
 *
 * Design: light canvas, a top app bar, a CORAL "OFFICIAL RACE PASS" pill over
 * an R22 pass card (dark event band, serif 62 bib, runner lines, bordered QR,
 * sand footer), the expo card, and a carbon "Save Pass Offline" button at the
 * foot of a space-between column.
 */
export default function DigitalBibScreen() {
  const router = useRouter();
  const { eventId } = useLocalSearchParams<{ eventId: string }>();
  const { data, isLoading, isError, error, refetch } = useRaceDetail(eventId ?? '');
  const { data: fresh } = useBibToken(eventId ?? '', Boolean(data?.bib));
  // Server-corrected: a phone clock a minute off would otherwise always show
  // the offline payload (fast) or an expired live token (slow).
  const now = useServerNow(5_000);

  const [saved, setSaved] = useState<OfflinePass | null>(null);
  useEffect(() => {
    let cancelled = false;
    if (eventId) {
      void loadOfflinePass(eventId).then((p) => {
        if (!cancelled) setSaved(p);
      });
    }
    return () => {
      cancelled = true;
    };
  }, [eventId]);

  const [saving, setSaving] = useState(false);
  const [savedNow, setSavedNow] = useState(false);

  // Once the server has answered, it decides — a withdrawn bib stays withdrawn.
  // Until then (or with no signal at all) the copy on the phone stands in.
  const live = data ? data.bib : null;
  const pass = data ? data.bib : saved;
  const back = () => router.back();

  if (!pass) {
    if (isError && !data) {
      return (
        <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
          <TopBar onBack={back} />
          <ErrorState error={error} onRetry={() => void refetch()} />
        </SafeAreaView>
      );
    }
    if (isLoading || !data) {
      return (
        <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
          <TopBar onBack={back} />
          <View style={styles.center}>
            <ActivityIndicator color={colors.coralBrand} />
          </View>
        </SafeAreaView>
      );
    }
    return (
      <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
        <TopBar onBack={back} />
        <View style={styles.center}>
          <Text style={styles.emptyTitle}>Your bib will appear here once it is allocated.</Text>
        </View>
      </SafeAreaView>
    );
  }

  // A short-lived token only while it has life left in it (a refresh that
  // failed offline leaves the last one behind); otherwise the offline signature.
  const unexpired = (t: { qrToken: string; qrExpiresAt: string } | null | undefined) =>
    t && Date.parse(t.qrExpiresAt) - now > 10_000 ? t.qrToken : null;
  const qrValue = live ? (unexpired(fresh) ?? unexpired(live) ?? live.offlinePayload) : pass.offlinePayload;

  const event = data?.event;
  // With no signal, the race-morning facts come from the copy on the phone too.
  const expo = event?.expo ?? saved?.expo ?? null;
  const flagOffTime = event?.flagOffTime ?? saved?.flagOffTime ?? null;
  const tierLabel = pass.tier === 'PREMIUM' ? 'Premium VIP' : 'Classic';

  // The pass is already kept automatically whenever the race detail loads;
  // the button makes that explicit and refreshes the stored copy on demand.
  const savePass = async () => {
    if (!live || !eventId) {
      // Nothing newer to store — the pass on screen IS the saved one.
      Alert.alert(
        'Saved on this phone',
        'This pass opens and scans at the gate even with no signal.',
      );
      return;
    }
    setSaving(true);
    try {
      await saveOfflinePass(eventId, live, offlinePassGeneration(), event);
      setSaved(await loadOfflinePass(eventId));
      setSavedNow(true);
    } catch {
      Alert.alert('Couldn’t save the pass', 'Your phone’s secure storage is unavailable. Try again.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
      <TopBar onBack={back} />
      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <View style={styles.topBlock}>
          <TagPill label="Official race pass · expo scan" tone="CORAL" style={styles.pill} />

          <View style={styles.pass}>
            <View style={styles.band}>
              <Text style={styles.bandText} numberOfLines={2}>
                {pass.eventName.toUpperCase()} · {pass.category}
              </Text>
            </View>
            <Text style={styles.bibNumber} adjustsFontSizeToFit numberOfLines={1}>
              {pass.bibNumber}
            </Text>
            <Text style={styles.runner} numberOfLines={1}>
              {pass.participantName || 'Registered runner'}
            </Text>
            <Text style={styles.wave}>
              {tierLabel} Runner{flagOffTime ? ` · ${flagOffTime}` : ''}
            </Text>
            <View style={styles.qrBox}>
              <QRCode value={qrValue} size={108} backgroundColor="white" color={colors.carbon950} />
            </View>
            <View style={styles.footer}>
              <Text style={styles.footerText}>
                {expo?.instructions ||
                  'Show this QR code at the expo bib counter to collect your race timing chip, bib, and t-shirt.'}
              </Text>
            </View>
          </View>

          {expo ? (
            <View style={styles.expoCard}>
              {expo.venue ? <ExpoRow label="Expo Venue" value={expo.venue} /> : null}
              {expo.pickupWindow ? <ExpoRow label="Pickup Window" value={expo.pickupWindow} /> : null}
              {expo.requiredDocuments.length ? (
                <ExpoRow label="Mandatory" value={expo.requiredDocuments.join(', ')} accent />
              ) : null}
            </View>
          ) : null}
        </View>

        <View style={styles.bottomBlock}>
          <View style={styles.offlineNote}>
            <Ionicons name="cloud-offline-outline" size={16} color={colors.textMuted} />
            <Text style={styles.offlineText}>
              {live
                ? 'Race pass saved offline in device storage — it works at the gate without a signal.'
                : 'No signal — showing the race pass saved on this phone. It still scans at the gate.'}
            </Text>
          </View>
          <Pressable
            style={styles.saveBtn}
            onPress={() => void savePass()}
            disabled={saving}
            accessibilityRole="button"
            accessibilityState={{ busy: saving }}
          >
            {saving ? (
              <ActivityIndicator color={colors.paperWhite} />
            ) : (
              <>
                {savedNow || !live ? (
                  <MaterialIcons name="check-circle" size={18} color={colors.liveEmerald} />
                ) : null}
                <Text style={styles.saveLabel}>
                  {savedNow ? 'Pass Saved Offline' : !live ? 'Saved on This Phone' : 'Save Pass Offline'}
                </Text>
              </>
            )}
          </Pressable>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

/** Design TopAppBar: CanvasBg, ArrowBack, "Digital Bib & Pass" serif 20 Bold. */
function TopBar({ onBack }: { onBack: () => void }) {
  return (
    <View style={styles.topBar}>
      <Pressable
        onPress={onBack}
        style={styles.backBtn}
        hitSlop={4}
        accessibilityRole="button"
        accessibilityLabel="Go back"
      >
        <MaterialIcons name="arrow-back" size={24} color={colors.textPrimary} />
      </Pressable>
      <Text style={styles.topTitle} accessibilityRole="header">
        Digital Bib & Pass
      </Text>
    </View>
  );
}

/** Expo card row: TextMuted 12 label, 12 Bold value; the mandatory ID in coral. */
function ExpoRow({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <View style={styles.expoRow}>
      <Text style={styles.expoLabel}>{label}</Text>
      <Text style={[styles.expoValue, accent && { color: colors.coralBrand }]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  emptyTitle: {
    textAlign: 'center',
    fontFamily: FONTS.serif,
    fontSize: 20,
    lineHeight: 25,
    fontWeight: '500',
    color: colors.textPrimary,
  },

  topBar: { height: 64, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 4 },
  backBtn: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  topTitle: {
    flex: 1,
    fontFamily: FONTS.serif,
    fontSize: 20,
    fontWeight: '700',
    color: colors.textPrimary,
  },

  // Column padding(20, 12), centred, SpaceBetween — the save button sits at the foot.
  scroll: { flexGrow: 1, justifyContent: 'space-between', paddingHorizontal: 20, paddingVertical: 12 },
  topBlock: { alignItems: 'center' },
  pill: { alignSelf: 'center' },

  pass: {
    marginTop: 14,
    alignSelf: 'stretch',
    alignItems: 'center',
    borderRadius: radii.hero,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    overflow: 'hidden',
  },
  band: {
    alignSelf: 'stretch',
    alignItems: 'center',
    backgroundColor: colors.carbon950,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  bandText: {
    textAlign: 'center',
    fontFamily: FONTS.sans,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 0.8,
    color: colors.paperWhite,
  },
  bibNumber: {
    marginTop: 18,
    paddingHorizontal: 16,
    fontFamily: FONTS.serif,
    fontSize: 62,
    lineHeight: 70,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  runner: { paddingHorizontal: 16, fontFamily: FONTS.sans, fontSize: 17, fontWeight: '700', color: colors.textPrimary },
  wave: {
    marginBottom: 16,
    paddingHorizontal: 16,
    textAlign: 'center',
    fontFamily: FONTS.sans,
    fontSize: 12,
    color: colors.textMuted,
  },
  // 130dp box, RCS 12, 1dp BorderRule, padding 10 → a 108dp code inside.
  qrBox: {
    width: 130,
    height: 130,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    backgroundColor: colors.paperWhite,
    alignItems: 'center',
    justifyContent: 'center',
  },
  footer: {
    marginTop: 16,
    alignSelf: 'stretch',
    alignItems: 'center',
    backgroundColor: colors.surfaceSand,
    padding: 14,
  },
  footerText: {
    textAlign: 'center',
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    lineHeight: 16,
    color: colors.textSecondary,
  },

  expoCard: {
    marginTop: 18,
    alignSelf: 'stretch',
    borderRadius: radii.lg,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    padding: 14,
    gap: 6,
  },
  expoRow: { flexDirection: 'row', justifyContent: 'space-between', gap: 12 },
  expoLabel: { fontFamily: FONTS.sans, fontSize: 12, color: colors.textMuted },
  expoValue: {
    flex: 1,
    textAlign: 'right',
    fontFamily: FONTS.sans,
    fontSize: 12,
    fontWeight: '700',
    color: colors.textPrimary,
  },

  bottomBlock: { marginTop: 18, gap: 10 },
  offlineNote: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 4 },
  offlineText: { flex: 1, fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: 16, color: colors.textMuted },
  // Button(h50, RCS 14, Carbon900) · "Save Pass Offline" 13.5 Bold.
  saveBtn: {
    height: 50,
    borderRadius: radii.option,
    backgroundColor: colors.carbon900,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  saveLabel: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.paperWhite },
});
