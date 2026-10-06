import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import type { MarathonEvent, RaceDetailResponse } from '@th/types';
import { useClaimUpgrade, useRaceDetail } from '@/api/hooks';
import { ApiRequestError } from '@/api/client';
import { CheckoutSheet, type CheckoutItem } from '@/components/CheckoutSheet';
import { ParticipantEditor } from '@/components/ParticipantEditor';
import { ReferAndWinCard } from '@/components/ReferAndWinCard';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, layout, radii } from '@/theme';
import { formatPaise } from '@/lib/time';
import { formatPhone } from '@/lib/profileLabels';
import { ErrorState } from '@/components/QueryState';

/**
 * Race detail — PRD §8.3, design RaceDetailScreenKt. Everything currently on
 * the web dashboard.
 *
 * Layout follows the design: a canvas "Race Details" app bar, then a padded
 * (18×12) column — navy registration summary → Premium upgrade banner →
 * "Event logistics" → Refer & Win → collapsible "Race FAQs". There is no event
 * photo here; the photo lives on the tab's race box.
 *
 * Two edge cases are handled by ABSENCE rather than by an error state, exactly
 * as §8.3 requires: when Premium is sold out or the user already holds it, the
 * upgrade banner is not rendered at all — it is never shown as a failing action.
 * The server decides; this screen simply renders `upgradeOffer` when present.
 */

const GUTTER = layout.screenGutter;

const longDate = (iso: string) =>
  new Date(iso).toLocaleDateString('en-IN', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });

export default function RaceDetailScreen() {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  // `distance` arrives from the tab's distance chips, so the pick carries over.
  // `edit=participant` arrives from the profile's "Race participant details"
  // row, which should land in the editor rather than on the race page.
  const { eventId, distance, edit } = useLocalSearchParams<{
    eventId: string;
    distance?: string;
    edit?: string;
  }>();
  const { data, isLoading, isError, error, refetch } = useRaceDetail(eventId ?? '');
  const [checkout, setCheckout] = useState<CheckoutItem | null>(null);
  const [editing, setEditing] = useState(false);

  const openedEditor = useRef(false);
  const editable = Boolean(data?.event.registration && data.event.registration.status !== 'COMPLETED');
  useEffect(() => {
    if (edit === 'participant' && editable && !openedEditor.current) {
      openedEditor.current = true;
      setEditing(true);
    }
  }, [edit, editable]);

  const back = () => router.back();

  if (isError && !data) {
    return (
      <SafeAreaView style={styles.root} edges={['top']}>
        <TopBar title="Race Details" onBack={back} />
        <ErrorState error={error} onRetry={() => void refetch()} />
      </SafeAreaView>
    );
  }
  if (isLoading || !data) {
    return (
      <SafeAreaView style={styles.root} edges={['top']}>
        <TopBar title="Race Details" onBack={back} />
        <View style={styles.center}>
          <ActivityIndicator color={colors.coralBrand} />
        </View>
      </SafeAreaView>
    );
  }

  const { event, bib, kit, referral, upgradeOffer, faqs } = data;
  const reg = event.registration;
  // After the race the entry is history: no edits, no bib — the result instead (§8.4).
  const completed = reg?.status === 'COMPLETED';
  const openResults = () =>
    router.push({ pathname: '/race/[eventId]/results', params: { eventId: event.id } });
  const openBib = () => router.push({ pathname: '/bib/[eventId]', params: { eventId: event.id } });

  return (
    <SafeAreaView style={styles.root} edges={['top']}>
      <TopBar title="Race Details" onBack={back} />
      <ScrollView
        contentContainerStyle={[styles.scroll, { paddingBottom: 12 + insets.bottom }]}
        showsVerticalScrollIndicator={false}
      >
        {reg ? (
          <View style={styles.summary}>
            <SummaryBackground />
            <View style={styles.summaryPills}>
              <TagPill label={`REG: ${reg.registrationRef}`} tone="CORAL" />
              <TagPill label={reg.tier === 'PREMIUM' ? 'Premium' : 'Classic'} tone="GOLD" />
            </View>
            <Text style={styles.summaryName}>{event.name}</Text>
            <Text style={styles.summaryMeta}>
              {reg.category} · {longDate(event.startsAt)}
              {reg.bibNumber ? ` · Bib ${reg.bibNumber}` : ''}
            </Text>
            {completed ? (
              <Pressable style={styles.coralBtn46} onPress={openResults} accessibilityRole="button">
                <Text style={styles.btnLabel13}>Check Result & Certificate</Text>
              </Pressable>
            ) : bib ? (
              <Pressable style={styles.coralBtn46} onPress={openBib} accessibilityRole="button">
                <Text style={styles.btnLabel13}>Open Digital Bib & QR Pass</Text>
              </Pressable>
            ) : (
              // No dead button: until a bib exists, say when it will.
              <Text style={styles.summaryNote}>
                Your digital bib and QR pass appear here as soon as your bib number is allocated.
              </Text>
            )}
          </View>
        ) : (
          <RegisterCard
            event={event}
            initialDistance={distance}
            onRegister={setCheckout}
          />
        )}

        {/* §8.3 edge case: event rescheduled is handled on the box and here. */}
        {event.rescheduledFrom ? (
          <View style={styles.notice}>
            <MaterialIcons name="event-repeat" size={18} color={colors.goldAccent} />
            <Text style={styles.noticeText}>
              This edition was rescheduled
              {Number.isNaN(Date.parse(event.rescheduledFrom))
                ? ''
                : ` from ${longDate(event.rescheduledFrom)}`}
              . Your registration transfers automatically with no penalty.
            </Text>
          </View>
        ) : null}

        {/* Rendered only when the server says an upgrade is actually available. */}
        {upgradeOffer?.available && reg ? (
          upgradeOffer.freeClaim ? (
            <FreeUpgradeCard event={event} benefits={upgradeOffer.benefits} />
          ) : (
            <Pressable
              style={styles.upgrade}
              onPress={() =>
                setCheckout({
                  request: { productType: 'PREMIUM_UPGRADE', productId: event.id, eventId: event.id },
                  title: 'Premium VIP Upgrade',
                  subtitle: `${event.name} · you pay only the net difference`,
                  displayPaise: upgradeOffer.netDifferencePaise,
                })
              }
              accessibilityRole="button"
            >
              <UpgradeBackground />
              <View style={{ flex: 1 }}>
                <TagPill label="Premium upgrade" tone="GOLD" />
                <Text style={styles.upgradeTitle}>Make it a VIP race morning</Text>
                <Text style={styles.upgradeBody}>
                  Pay net difference {formatPaise(upgradeOffer.netDifferencePaise)}:{' '}
                  {listOf(upgradeOffer.benefits)}.
                </Text>
              </View>
              <MaterialIcons name="chevron-right" size={24} color={colors.paperWhite} />
            </Pressable>
          )
        ) : null}

        <Logistics data={data} onEdit={reg && !completed ? () => setEditing(true) : undefined} />

        {/* §8.3: Refer & Win — the server only sends it while the race is ahead. */}
        {referral && reg && !completed ? (
          <View style={styles.block18}>
            <ReferAndWinCard referral={referral} eventName={event.name} variant="detail" />
          </View>
        ) : null}

        {faqs.length ? (
          <View style={styles.block18}>
            <Text style={styles.blockTitle}>Race FAQs</Text>
            <View style={{ marginTop: 6 }}>
              {faqs.map((f) => (
                <Faq key={f.question} question={f.question} answer={f.answer} />
              ))}
            </View>
          </View>
        ) : null}
      </ScrollView>

      <CheckoutSheet item={checkout} onClose={() => setCheckout(null)} />
      {reg ? (
        <ParticipantEditor
          eventId={event.id}
          visible={editing}
          initialSize={data.participant?.tshirtSize ?? kit?.tshirtSize ?? null}
          initialContactName={data.participant?.emergencyContactName ?? null}
          initialContactPhone={data.participant?.emergencyContactPhone ?? null}
          onClose={() => setEditing(false)}
        />
      ) : null}
    </SafeAreaView>
  );
}

/** "Reserved parking, Kit delivery & Separate start wave" */
function listOf(items: string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} & ${items[items.length - 1]}`;
}

/** Design TopAppBar: CanvasBg, ArrowBack IconButton, serif 20 Bold title. */
function TopBar({ title, onBack }: { title: string; onBack: () => void }) {
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
      <Text style={styles.topTitle} numberOfLines={1} accessibilityRole="header">
        {title}
      </Text>
    </View>
  );
}

/** $lambda$14: linearGradient(#1E284A → #11172E) — Compose's default diagonal. */
function SummaryBackground() {
  return (
    <LinearGradient
      colors={['#1E284A', '#11172E']}
      start={{ x: 0, y: 0 }}
      end={{ x: 1, y: 1 }}
      style={StyleSheet.absoluteFill}
    />
  );
}

/** $lambda$20: linearGradient(#7A4A12 → #C98A16). */
function UpgradeBackground() {
  return (
    <LinearGradient
      colors={['#7A4A12', colors.goldAccent]}
      start={{ x: 0, y: 0 }}
      end={{ x: 1, y: 1 }}
      style={StyleSheet.absoluteFill}
    />
  );
}

// ── Refer & Win reward — the guaranteed upgrade, claimed free ───────────────

const CLAIM_ERRORS: Record<string, string> = {
  NO_FREE_UPGRADE: 'There’s no earned upgrade left to claim on this account.',
  ALREADY_PREMIUM: 'This entry is already Premium VIP.',
  REGISTRATION_CLOSED: 'This race has already started, so the upgrade can no longer be applied.',
  NOT_REGISTERED: 'We couldn’t find your registration for this race.',
};

function claimErrorText(err: unknown): string {
  if (err instanceof ApiRequestError) {
    if (err.status === 0) return err.body.message; // offline / timeout — already user-facing
    const known = CLAIM_ERRORS[err.body.code];
    if (known) return known;
  }
  return 'Something went wrong on our side. Please try again.';
}

/**
 * Same banner, different deal: 5 referrals earned this upgrade (§8.3), so it
 * is claimed, not paid for — no checkout, one confirm. The server re-checks
 * everything (still unclaimed, still Classic, race still ahead).
 */
function FreeUpgradeCard({ event, benefits }: { event: MarathonEvent; benefits: string[] }) {
  const claim = useClaimUpgrade();

  const confirm = () => {
    if (claim.isPending) return;
    Alert.alert(
      'Claim your free Premium upgrade?',
      `Your ${event.name} entry moves to Premium VIP at no cost. The Refer & Win upgrade can be claimed once, on one race.`,
      [
        { text: 'Not now', style: 'cancel' },
        {
          text: 'Claim upgrade',
          onPress: () =>
            claim.mutate(event.id, {
              onSuccess: () =>
                Alert.alert('You’re Premium VIP', `Your ${event.name} entry is now Premium VIP. Enjoy race morning!`),
              onError: (err) => Alert.alert('Couldn’t claim the upgrade', claimErrorText(err)),
            }),
        },
      ],
    );
  };

  return (
    <Pressable style={[styles.upgrade, styles.upgradeColumn]} onPress={confirm} accessibilityRole="button">
      <UpgradeBackground />
      <TagPill label="Premium upgrade" tone="GOLD" />
      <Text style={styles.upgradeTitle}>Your free Premium upgrade</Text>
      <Text style={styles.upgradeBody}>
        Earned through Refer & Win — 5 friends registered with your code. {listOf(benefits)}, at no
        cost.
      </Text>
      <Pressable
        style={styles.claimBtn}
        onPress={confirm}
        disabled={claim.isPending}
        accessibilityRole="button"
        accessibilityState={{ busy: claim.isPending }}
      >
        {claim.isPending ? (
          <ActivityIndicator color="#7A4A12" />
        ) : (
          <Text style={styles.claimLabel}>Claim free Premium upgrade</Text>
        )}
      </Pressable>
    </Pressable>
  );
}

// ── Not registered yet — the design's distance picker ───────────────────────

/**
 * The design has no unregistered detail screen; this reuses its own pieces:
 * the navy summary for the headline, then FreePrimaryRaceHero's "CHOOSE YOUR
 * DISTANCE" chips (CoralTint/CoralBrand selected, SurfaceSand otherwise), the
 * segment control for the tier, and the From-₹ / Register footer.
 */
function RegisterCard({
  event,
  initialDistance,
  onRegister,
}: {
  event: MarathonEvent;
  initialDistance?: string;
  onRegister: (item: CheckoutItem) => void;
}) {
  const options = event.distanceOptions;
  const [code, setCode] = useState<string | undefined>(
    () =>
      (options.find((d) => d.code === initialDistance) ??
        options.find((d) => d.code === '21K' && d.registrationOpen) ??
        options.find((d) => d.registrationOpen) ??
        options[0])?.code,
  );
  // PRD §2: 4 distances × 2 tiers.
  const [tier, setTier] = useState<'CLASSIC' | 'PREMIUM'>('CLASSIC');
  const d = options.find((o) => o.code === code);
  const premiumGone = tier === 'PREMIUM' && Boolean(d?.premiumSoldOut);
  const open = Boolean(event.registrationOpen && d?.registrationOpen && !premiumGone);
  const price = d ? d.pricePaise[tier] : 0;
  const was = d?.wasPricePaise?.[tier];

  return (
    <>
      <View style={styles.summary}>
        <SummaryBackground />
        <View style={styles.summaryPills}>
          {event.registrationOpen ? (
            <TagPill label={`Registrations open · ${new Date(event.startsAt).getFullYear()}`} tone="CORAL" />
          ) : (
            <TagPill label="Registrations closed" tone="NEUTRAL" />
          )}
        </View>
        <Text style={styles.summaryName}>{event.name}</Text>
        <Text style={[styles.summaryMeta, { marginBottom: 0 }]}>
          {event.city} · {longDate(event.startsAt)} · {event.venue}
        </Text>
      </View>

      <View style={styles.pickCard}>
        <Text style={styles.eyebrow}>CHOOSE YOUR DISTANCE</Text>
        <View style={styles.chipRow}>
          {options.map((o) => {
            const on = o.code === code;
            return (
              <Pressable
                key={o.code}
                style={[styles.distChip, on && styles.distChipOn]}
                onPress={() => setCode(o.code)}
                accessibilityRole="radio"
                accessibilityState={{ selected: on }}
              >
                <Text style={[styles.distChipText, on && { color: colors.coralBrand }]}>{o.code}</Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={[styles.eyebrow, { marginTop: 14 }]}>ENTRY TYPE</Text>
        <View style={styles.segment}>
          {(['CLASSIC', 'PREMIUM'] as const).map((t) => {
            const on = tier === t;
            return (
              <Pressable
                key={t}
                style={[styles.segmentItem, on && styles.segmentItemOn]}
                onPress={() => setTier(t)}
                accessibilityRole="radio"
                accessibilityState={{ selected: on }}
              >
                <Text style={[styles.segmentText, on && styles.segmentTextOn]}>
                  {t === 'CLASSIC' ? 'Classic' : 'Premium VIP'}
                </Text>
              </Pressable>
            );
          })}
        </View>

        <View style={styles.pickFooter}>
          <View style={{ flexShrink: 1 }}>
            <View style={styles.priceRow}>
              <Text style={styles.price}>{formatPaise(price)}</Text>
              {was && was > price ? <Text style={styles.strike}>{formatPaise(was)}</Text> : null}
            </View>
            <Text style={styles.priceNote}>
              {tier === 'PREMIUM' ? 'Premium VIP entry · race-morning perks' : 'Classic Entry with chip & t-shirt'}
            </Text>
          </View>
          {/* §8.3: a closed distance shows a closed state, never a register action. */}
          {open && d ? (
            <Pressable
              style={styles.registerBtn}
              onPress={() =>
                onRegister({
                  request: {
                    productType: 'MARATHON_REGISTRATION',
                    productId: `${event.id}:${d.code}:${tier}`,
                    eventId: event.id,
                    category: d.code,
                    tier,
                  },
                  title: `${event.name} · ${d.label}`,
                  subtitle: `${tier === 'PREMIUM' ? 'Premium VIP' : 'Classic'} entry · ${event.city}`,
                  displayPaise: price,
                })
              }
              accessibilityRole="button"
            >
              <Text style={styles.btnLabel13}>Register</Text>
            </Pressable>
          ) : (
            <TagPill
              label={!event.registrationOpen ? 'Entries closed' : premiumGone ? 'Premium sold out' : 'Closed'}
              tone="NEUTRAL"
            />
          )}
        </View>
      </View>
    </>
  );
}

// ── Event logistics — one card, design DetailItemRows ───────────────────────

/**
 * Design: "Event logistics" (18 Bold Serif) then one outlined R16 card of
 * DetailItemRows and an outlined "Edit Participant Details" button. The kit
 * courier and expo pickup facts (separate cards before) fold into it.
 */
function Logistics({ data, onEdit }: { data: RaceDetailResponse; onEdit?: () => void }) {
  const { event, kit } = data;
  const expo = event.expo;
  const registered = Boolean(event.registration);
  const courier = [kit?.courierName, kit?.trackingRef ? `#${kit.trackingRef}` : null]
    .filter(Boolean)
    .join(' ');

  return (
    <View style={styles.block18}>
      <Text style={styles.blockTitle}>Event logistics</Text>
      <View style={styles.logisticsCard}>
        <Row label="Flag-off Time" value={event.flagOffTime} />
        <Row label="Venue" value={event.venue} />
        {expo?.venue ? <Row label="Expo Venue" value={expo.venue} /> : null}
        {expo?.pickupWindow ? <Row label="Expo Date" value={expo.pickupWindow} /> : null}
        {expo?.instructions ? <Row label="Bib Pickup" value={expo.instructions} /> : null}
        {expo?.requiredDocuments.length ? (
          <Row label="Mandatory" value={expo.requiredDocuments.join(', ')} />
        ) : null}
        {registered ? (
          <Row
            label="T-shirt Size"
            value={data.participant?.tshirtSize ?? kit?.tshirtSize ?? 'Not chosen yet'}
          />
        ) : null}
        {registered ? (
          <Row
            label="Emergency Contact"
            value={
              data.participant?.emergencyContactName || data.participant?.emergencyContactPhone
                ? [data.participant.emergencyContactName, data.participant.emergencyContactPhone && formatPhone(data.participant.emergencyContactPhone)]
                    .filter(Boolean)
                    .join(' · ')
                : 'Not added yet'
            }
          />
        ) : null}
        {kit ? (
          <Row label="Runner Kit" value={kit.status.replace(/_/g, ' ').toLowerCase()} capitalize />
        ) : null}
        {courier ? <Row label="Courier" value={courier} /> : null}

        {onEdit ? (
          <Pressable style={styles.editBtn} onPress={onEdit} accessibilityRole="button">
            <Text style={styles.editLabel}>Edit Participant Details</Text>
          </Pressable>
        ) : null}
      </View>
    </View>
  );
}

/** DetailItemRow: padV 6, TextMuted 12 label, 12.5 Bold value, no dividers. */
function Row({
  label,
  value,
  capitalize,
}: {
  label: string;
  value: string;
  /** Only for our own enum labels — never refs, codes or names, whose case matters. */
  capitalize?: boolean;
}) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      {/* A long venue wraps on the right instead of pushing the row off the card. */}
      <Text style={[styles.rowValue, capitalize && { textTransform: 'capitalize' }]}>{value}</Text>
    </View>
  );
}

/** RaceFaqAccordion: its own R12 card per question; the answer only when open. */
function Faq({ question, answer }: { question: string; answer: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Pressable
      style={styles.faq}
      onPress={() => setOpen((o) => !o)}
      accessibilityRole="button"
      accessibilityState={{ expanded: open }}
    >
      <View style={styles.faqHead}>
        <Text style={styles.faqQ}>{question}</Text>
        <MaterialIcons
          name={open ? 'keyboard-arrow-up' : 'keyboard-arrow-down'}
          size={24}
          color={colors.textMuted}
        />
      </View>
      {open ? <Text style={styles.faqA}>{answer}</Text> : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  // Design LazyColumn contentPadding(18, 12).
  scroll: { paddingHorizontal: GUTTER, paddingTop: 12 },

  // M3 TopAppBar: 64 high, 4dp edge inset, 48dp icon button, title right after it.
  topBar: { height: 64, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 4 },
  backBtn: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  topTitle: {
    flex: 1,
    fontFamily: FONTS.serif,
    fontSize: 20,
    fontWeight: '700',
    color: colors.textPrimary,
  },

  // Navy summary — RCS 20, padding 18.
  summary: { borderRadius: radii.xl, overflow: 'hidden', padding: 18 },
  summaryPills: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', gap: 6 },
  summaryName: {
    marginTop: 8,
    fontFamily: FONTS.serif,
    fontSize: 24,
    lineHeight: 29,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  summaryMeta: {
    marginTop: 2,
    marginBottom: 14,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 16,
    color: 'rgba(255,255,255,0.8)',
  },
  summaryNote: { fontFamily: FONTS.sans, fontSize: 12, lineHeight: 17, color: 'rgba(255,255,255,0.8)' },
  coralBtn46: {
    height: 46,
    borderRadius: radii.md,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  // M3 Button default label (labelLarge): 13 Bold.
  btnLabel13: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.paperWhite },

  notice: {
    marginTop: 14,
    flexDirection: 'row',
    gap: 10,
    backgroundColor: colors.goldTint,
    borderRadius: radii.md,
    padding: 12,
  },
  noticeText: {
    flex: 1,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
  },

  // Premium upgrade — RCS 16, padding 16, Spacer 14 above.
  upgrade: {
    marginTop: 14,
    borderRadius: radii.lg,
    overflow: 'hidden',
    padding: 16,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  upgradeColumn: { flexDirection: 'column', alignItems: 'stretch', gap: 0 },
  upgradeTitle: {
    marginTop: 4,
    fontFamily: FONTS.sans,
    fontSize: 14,
    lineHeight: 19,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  upgradeBody: {
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    lineHeight: 16,
    color: 'rgba(255,255,255,0.85)',
  },
  claimBtn: {
    marginTop: 12,
    height: 44,
    borderRadius: 10,
    backgroundColor: colors.paperWhite,
    alignItems: 'center',
    justifyContent: 'center',
  },
  claimLabel: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: '#7A4A12' },

  block18: { marginTop: 18 },
  blockTitle: {
    fontFamily: FONTS.serif,
    fontSize: 18,
    lineHeight: 23,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  logisticsCard: {
    marginTop: 8,
    borderRadius: radii.lg,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    padding: 14,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    paddingVertical: 6,
  },
  rowLabel: { fontFamily: FONTS.sans, fontSize: 12, color: colors.textMuted },
  rowValue: {
    flex: 1,
    textAlign: 'right',
    fontFamily: FONTS.sans,
    fontSize: 12.5,
    lineHeight: 17,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  editBtn: {
    marginTop: 10,
    height: 42,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: colors.borderRule,
    alignItems: 'center',
    justifyContent: 'center',
  },
  editLabel: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.textPrimary },

  faq: {
    marginVertical: 3,
    borderRadius: radii.md,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderRule,
    padding: 14,
  },
  faqHead: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  faqQ: {
    flex: 1,
    fontFamily: FONTS.sans,
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  faqA: {
    marginTop: 6,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
  },

  // Register card (unregistered)
  pickCard: {
    marginTop: 14,
    borderRadius: radii.lg,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    padding: 16,
  },
  eyebrow: {
    fontFamily: FONTS.sans,
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.8,
    color: colors.textMuted,
  },
  chipRow: { marginTop: 8, flexDirection: 'row', gap: 8 },
  distChip: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 8,
    borderRadius: radii.sm,
    borderWidth: 1,
    borderColor: colors.borderRule,
    backgroundColor: colors.surfaceSand,
  },
  distChipOn: { backgroundColor: colors.coralTint, borderColor: colors.coralBrand },
  distChipText: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.textPrimary },
  segment: {
    marginTop: 8,
    flexDirection: 'row',
    backgroundColor: colors.surfaceSand,
    borderRadius: radii.md,
    padding: 4,
  },
  segmentItem: { flex: 1, alignItems: 'center', paddingVertical: 8, borderRadius: 10 },
  segmentItemOn: { backgroundColor: colors.paperWhite },
  segmentText: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '500', color: colors.textSecondary },
  segmentTextOn: { fontWeight: '700', color: colors.coralBrand },
  pickFooter: {
    marginTop: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
  },
  priceRow: { flexDirection: 'row', alignItems: 'baseline', gap: 8 },
  price: {
    fontFamily: FONTS.serif,
    fontSize: 22,
    lineHeight: 27,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  strike: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    color: colors.textMuted,
    textDecorationLine: 'line-through',
  },
  priceNote: { fontFamily: FONTS.sans, fontSize: 11, lineHeight: 15, color: colors.textMuted },
  registerBtn: {
    height: 44,
    borderRadius: 10,
    paddingHorizontal: 24,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
