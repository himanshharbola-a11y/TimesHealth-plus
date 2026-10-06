import { Image, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { MaterialIcons } from '@expo/vector-icons';
import type { ComponentProps, ReactNode } from 'react';
import type {
  HeroMyRace,
  HeroRaceResult,
  HeroSellMarathon,
  HeroSellYoga,
  HeroSlot,
  HeroYogaRenew,
  HeroYogaSession,
} from '@th/types';
import { TagPill, type TagPillTone } from '@/components/TagPill';
import { formatDayAndTime, formatTimeOfDay, useCountdown } from '@/lib/time';
import { FONTS, colors, radii } from '@/theme';
import { CardImage, type Gradient } from './Cards';

/**
 * Hero banner, slot 1 — PRD §6.1. Priority is resolved on the server; this
 * file only renders what it is handed and contains none of that logic.
 *
 * Visuals are the design's HeroYogaSlot1 / HeroRaceSlot1 / HeroSellYogaSlot1 /
 * HeroYogaExpiredSlot1 (HomeScreenKt): a 22dp photo card with a 1dp rule, NO
 * drop shadow, a black scrim (35% → 88%) for legibility, a TagPill status
 * line, a serif title and a full-width 48dp CTA. Slot 2 is the compact white
 * card in HeroSecondaryCard.tsx.
 */

type IconName = ComponentProps<typeof MaterialIcons>['name'];

const HERO_KINDS: readonly string[] = [
  'YOGA_SESSION',
  'YOGA_RENEW',
  'MY_RACE',
  'RACE_RESULT',
  'SELL_YOGA',
  'SELL_MARATHON',
] satisfies readonly HeroSlot['kind'][];

/**
 * Forward compatibility (§6, feed.ts): a newer server may send a slot kind
 * this build has never heard of. It is filtered out before rendering — never
 * a crash, never an empty card.
 */
export function isKnownHeroSlot(slot: unknown): slot is HeroSlot {
  if (!slot || typeof slot !== 'object') return false;
  const kind = (slot as { kind?: unknown }).kind;
  return typeof kind === 'string' && HERO_KINDS.includes(kind);
}

/** "Sun, 20 Oct 2026" — the design's MarathonEvent.dateStr format. */
export function formatRaceDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString('en-IN', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

// Fallbacks when a slot carries no photo: plumBrand → plumDeep for yoga, the
// design's night-navy for races. Both run diagonally like Brush.linearGradient.
const YOGA_FALLBACK: Gradient = { colors: [colors.plumBrand, colors.plumDeep] };
const RACE_FALLBACK: Gradient = { colors: ['#1E284A', '#11172E'] };
const HERO_SCRIM: Gradient = { colors: ['rgba(0,0,0,0.35)', 'rgba(0,0,0,0.88)'] };

const MONO = Platform.select({ ios: 'Menlo', default: 'monospace' });

interface Props {
  slot: HeroSlot;
  onPress: (slot: HeroSlot) => void;
}

export function HeroCard({ slot, onPress }: Props) {
  switch (slot.kind) {
    case 'YOGA_SESSION':
      return <YogaSessionHero slot={slot} onPress={onPress} />;
    case 'YOGA_RENEW':
      return <RenewHero slot={slot} onPress={onPress} />;
    case 'MY_RACE':
      return <MyRaceHero slot={slot} onPress={onPress} />;
    case 'RACE_RESULT':
    case 'SELL_YOGA':
    case 'SELL_MARATHON':
      return <PromoHero slot={slot} onPress={onPress} />;
    default:
      return null;
  }
}

// ── Yoga session — HeroYogaSlot1 ────────────────────────────────────────────

function YogaSessionHero({ slot, onPress }: { slot: HeroYogaSession; onPress: Props['onPress'] }) {
  // Ticks locally from the server-synced clock (§6.1) — never polls.
  const ticking = useCountdown(slot.state === 'STARTING_SOON' ? slot.startsAt : null);
  const seconds = ticking !== null && Number.isFinite(ticking) ? ticking : slot.secondsToStart;
  // The wait room counts down to the class itself: once it reaches zero the
  // class IS live, so the card says so rather than waiting for a refetch.
  const live = slot.state === 'LIVE' || (slot.state === 'STARTING_SOON' && seconds === 0);
  const mins = Math.max(1, Math.ceil((seconds ?? 0) / 60));

  let pill: { label: string; tone: TagPillTone };
  let cta = slot.ctaLabel;
  if (live) {
    // Never "Starting now" during the live hour — the class has started.
    pill = { label: 'Live now', tone: 'LIVE' };
    if (slot.state !== 'LIVE') cta = 'Join Live Session';
  } else if (slot.state === 'STARTING_SOON') {
    pill = { label: `Next session · Starts in ${mins} min`, tone: 'CORAL' };
    cta = `Starts in ${mins} min — ${slot.ctaLabel}`;
  } else if (slot.state === 'SCHEDULED_TODAY') {
    pill = { label: `Next session · ${formatTimeOfDay(slot.startsAt)}`, tone: 'CORAL' };
  } else {
    // §6.1 edge case: nothing left today → the day, not a timer.
    pill = { label: `Next session · ${formatDayAndTime(slot.startsAt)}`, tone: 'CORAL' };
  }
  // Only a live class or open wait room is joinable; a later session's CTA
  // opens the schedule, so it must not wear a "play" glyph.
  const joinable = live || slot.state === 'STARTING_SOON';

  // Older servers sent neither field; their subtitle ("with Apurva") still reads right.
  const meta = slot.instructorName
    ? slot.durationMinutes
      ? `${slot.instructorName} · ${slot.durationMinutes} min`
      : slot.instructorName
    : slot.subtitle;

  return (
    <PhotoShell
      theme="YOGA"
      imageUrl={slot.imageUrl}
      onPress={() => onPress(slot)}
      a11yLabel={`${pill.label}. ${slot.title}. ${cta}`}
    >
      <TagPill label={pill.label} tone={pill.tone} />
      <Text style={[styles.yogaTitle, { marginTop: 10 }]} numberOfLines={2}>
        {slot.title}
      </Text>
      {meta ? (
        <View style={styles.instructorRow}>
          {slot.instructorName ? (
            <InstructorAvatar name={slot.instructorName} uri={slot.instructorAvatarUrl} size={22} />
          ) : null}
          <Text style={styles.instructorText} numberOfLines={1}>
            {meta}
          </Text>
        </View>
      ) : null}
      <View style={{ marginTop: 16 }}>
        <HeroCta label={cta} icon={joinable ? 'play-arrow' : 'event'} />
      </View>
    </PhotoShell>
  );
}

/** CommonComponentsKt.InstructorAvatarImage: initials on plum→coral, photo over it. */
function InstructorAvatar({ name, uri, size }: { name: string; uri: string | null; size: number }) {
  const initials = name
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
  return (
    <View style={[styles.avatar, { width: size, height: size, borderRadius: size / 2 }]}>
      <LinearGradient
        colors={[colors.plumDeep, colors.coralBrand]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={StyleSheet.absoluteFill}
      />
      <Text style={[styles.avatarText, { fontSize: size * 0.38 }]}>{initials}</Text>
      {uri ? <Image source={{ uri }} style={StyleSheet.absoluteFill} resizeMode="cover" /> : null}
    </View>
  );
}

// ── My race — HeroRaceSlot1 ─────────────────────────────────────────────────

function MyRaceHero({ slot, onPress }: { slot: HeroMyRace; onPress: Props['onPress'] }) {
  const ticking = useCountdown(slot.isRaceDay ? null : slot.startsAt);
  const s =
    ticking !== null && Number.isFinite(ticking)
      ? ticking
      : Math.max(0, slot.daysRemaining ?? 0) * 86_400;
  const days = Math.floor(s / 86_400);
  const hours = Math.floor((s % 86_400) / 3600);
  const mins = Math.floor((s % 3600) / 60);

  const meta = [slot.category, formatRaceDate(slot.startsAt), slot.flagOffTime]
    .filter(Boolean)
    .join(' · ');
  // Generic on purpose: gate numbers differ per venue and are on the bib pass.
  const raceDayPill = slot.flagOffTime
    ? `Flag-off ${slot.flagOffTime} · Report at gate`
    : 'Race day · Report at gate';

  return (
    <PhotoShell
      theme="RACE"
      imageUrl={slot.imageUrl}
      onPress={() => onPress(slot)}
      a11yLabel={`${slot.title}. ${slot.isRaceDay ? 'Race day' : `${days} days to go`}. ${slot.ctaLabel}`}
    >
      <View style={styles.spaceRow}>
        {/* The design keeps CORAL for both states; race-day urgency is the LIVE pill below. */}
        <TagPill label={slot.isRaceDay ? 'Race day' : 'Registered'} tone="CORAL" />
        {slot.bibNumber ? <Text style={styles.bib}>BIB #{slot.bibNumber}</Text> : null}
      </View>
      <Text style={[styles.heroTitle, { marginTop: 8 }]} numberOfLines={2}>
        {slot.title}
      </Text>
      {meta ? (
        <Text style={styles.raceMeta} numberOfLines={2}>
          {meta}
        </Text>
      ) : (
        <View style={{ height: 14 }} />
      )}
      {slot.isRaceDay ? (
        <View style={{ marginBottom: 14 }}>
          <TagPill label={raceDayPill} tone="LIVE" />
        </View>
      ) : (
        <View style={styles.countdownRow}>
          <CountdownBox value={String(days)} label="DAYS" />
          <CountdownBox value={String(hours).padStart(2, '0')} label="HOURS" />
          <CountdownBox value={String(mins).padStart(2, '0')} label="MINS" />
        </View>
      )}
      <HeroCta label={slot.ctaLabel} />
    </PhotoShell>
  );
}

function CountdownBox({ value, label }: { value: string; label: string }) {
  return (
    <View style={styles.cdBox}>
      <Text style={styles.cdValue}>{value}</Text>
      <Text style={styles.cdLabel}>{label}</Text>
    </View>
  );
}

// ── Sell yoga / sell marathon / race result — same card system ─────────────

const PROMO_HERO: Record<
  'RACE_RESULT' | 'SELL_YOGA' | 'SELL_MARATHON',
  { pill: string; tone: TagPillTone; theme: 'YOGA' | 'RACE' }
> = {
  // HeroSellYogaSlot1 copy.
  SELL_YOGA: { pill: 'Daily live programme', tone: 'CORAL', theme: 'YOGA' },
  SELL_MARATHON: { pill: 'TimesHealth+ Marathon', tone: 'CORAL', theme: 'RACE' },
  RACE_RESULT: { pill: 'Race completed', tone: 'GOLD', theme: 'RACE' },
};

function PromoHero({
  slot,
  onPress,
}: {
  slot: HeroSellYoga | HeroSellMarathon | HeroRaceResult;
  onPress: Props['onPress'];
}) {
  const look = PROMO_HERO[slot.kind] ?? PROMO_HERO.SELL_MARATHON;
  return (
    <PhotoShell
      theme={look.theme}
      imageUrl={slot.imageUrl}
      onPress={() => onPress(slot)}
      a11yLabel={`${look.pill}. ${slot.title.replace(/\n/g, ' ')}. ${slot.ctaLabel}`}
    >
      <TagPill label={look.pill} tone={look.tone} />
      {/* SELL_YOGA's title carries the design's own "\n" line break. */}
      <Text style={[styles.heroTitle, { marginTop: 8 }]} numberOfLines={3}>
        {slot.title}
      </Text>
      {slot.subtitle ? (
        <Text style={styles.heroBody} numberOfLines={3}>
          {slot.subtitle}
        </Text>
      ) : (
        <View style={{ height: 14 }} />
      )}
      <HeroCta label={slot.ctaLabel} />
    </PhotoShell>
  );
}

// ── Renew — HeroYogaExpiredSlot1: a light card, warm not cold (§5) ──────────

function RenewHero({ slot, onPress }: { slot: HeroYogaRenew; onPress: Props['onPress'] }) {
  return (
    <Pressable
      onPress={() => onPress(slot)}
      style={styles.renewCard}
      accessibilityRole="button"
      accessibilityLabel={`Membership expired. ${slot.title}. ${slot.ctaLabel}`}
    >
      <TagPill label="Membership expired" tone="GOLD" />
      <Text style={styles.renewTitle} numberOfLines={2}>
        {slot.title}
      </Text>
      {slot.subtitle ? (
        <Text style={styles.renewBody} numberOfLines={3}>
          {slot.subtitle}
        </Text>
      ) : (
        <View style={{ height: 14 }} />
      )}
      <HeroCta label={slot.ctaLabel} color={colors.plumBrand} />
    </Pressable>
  );
}

// ── Shared pieces ──────────────────────────────────────────────────────────

function PhotoShell({
  theme,
  imageUrl,
  onPress,
  a11yLabel,
  children,
}: {
  theme: 'YOGA' | 'RACE';
  imageUrl: string | null;
  onPress: () => void;
  a11yLabel: string;
  children: ReactNode;
}) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={a11yLabel}
      style={[styles.card, { borderColor: theme === 'YOGA' ? colors.plumLine : colors.borderRule }]}
    >
      <CardImage
        uri={imageUrl}
        fallback={theme === 'YOGA' ? YOGA_FALLBACK : RACE_FALLBACK}
        scrim={HERO_SCRIM}
      />
      <View style={styles.inner}>{children}</View>
    </Pressable>
  );
}

/** M3 Button as the design sizes it: full width, 48dp, 12dp corners. */
function HeroCta({
  label,
  color = colors.coralBrand,
  icon,
}: {
  label: string;
  color?: string;
  icon?: IconName;
}) {
  return (
    <View style={[styles.cta, { backgroundColor: color }]}>
      {icon ? <MaterialIcons name={icon} size={18} color={colors.paperWhite} /> : null}
      <Text style={styles.ctaText} numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

const WHITE_85 = 'rgba(255,255,255,0.85)';

const styles = StyleSheet.create({
  card: {
    borderRadius: radii.hero,
    borderWidth: 1,
    overflow: 'hidden',
    backgroundColor: colors.carbon900,
  },
  inner: { padding: 18 },
  spaceRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },

  yogaTitle: {
    fontFamily: FONTS.serif,
    fontSize: 23,
    lineHeight: 27,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  heroTitle: {
    fontFamily: FONTS.serif,
    fontSize: 24,
    lineHeight: 28,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  heroBody: {
    paddingTop: 4,
    paddingBottom: 14,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 19,
    color: WHITE_85,
  },

  instructorRow: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 4 },
  instructorText: {
    flexShrink: 1,
    fontFamily: FONTS.sans,
    fontSize: 12,
    color: 'rgba(255,255,255,0.9)',
  },
  avatar: { overflow: 'hidden', alignItems: 'center', justifyContent: 'center' },
  avatarText: { fontFamily: FONTS.sans, fontWeight: '700', color: colors.paperWhite },

  bib: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '700', color: WHITE_85 },
  raceMeta: {
    paddingTop: 2,
    paddingBottom: 12,
    fontFamily: FONTS.sans,
    fontSize: 12,
    color: WHITE_85,
  },
  countdownRow: { flexDirection: 'row', gap: 8, paddingBottom: 14 },
  cdBox: {
    alignItems: 'center',
    // #33000000
    backgroundColor: 'rgba(0,0,0,0.2)',
    borderRadius: radii.sm,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.2)',
    paddingHorizontal: 14,
    paddingVertical: 6,
  },
  cdValue: { fontFamily: MONO, fontSize: 18, fontWeight: '700', color: colors.paperWhite },
  cdLabel: {
    fontFamily: FONTS.sans,
    fontSize: 9,
    fontWeight: '700',
    color: 'rgba(255,255,255,0.7)',
  },

  renewCard: {
    borderRadius: radii.hero,
    borderWidth: 1,
    borderColor: colors.plumLine,
    backgroundColor: colors.plumTint,
    padding: 18,
  },
  renewTitle: {
    marginTop: 8,
    fontFamily: FONTS.serif,
    fontSize: 22,
    lineHeight: 27,
    fontWeight: '700',
    color: colors.plumDeep,
  },
  renewBody: {
    paddingTop: 4,
    paddingBottom: 14,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 19,
    color: colors.textSecondary,
  },

  cta: {
    height: 48,
    borderRadius: radii.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingHorizontal: 24,
  },
  ctaText: {
    flexShrink: 1,
    fontFamily: FONTS.sans,
    fontSize: 13.5,
    fontWeight: '700',
    // M3 Button content inherits labelLarge's +0.2 tracking.
    letterSpacing: 0.2,
    color: colors.paperWhite,
  },
});
