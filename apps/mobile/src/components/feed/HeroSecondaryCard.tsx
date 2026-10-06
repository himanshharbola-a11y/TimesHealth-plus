import { Pressable, StyleSheet, Text, View } from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import type { ComponentProps, ReactNode } from 'react';
import type { HeroMyRace, HeroSlot, HeroYogaSession } from '@th/types';
import { TagPill } from '@/components/TagPill';
import { formatDayAndTime, formatPaise, useCountdown } from '@/lib/time';
import { FONTS, colors, radii, tints } from '@/theme';
import { CardImage } from './Cards';
import { formatRaceDate } from './Hero';

/**
 * Hero slot 2 — PRD §6.1 "whatever loses drops to slot 2".
 *
 * The design gives the runner-up a compact WHITE row, not a second photo
 * banner (HomeScreenKt SecondaryRaceCard / SecondarySellMarathonCard /
 * SecondarySellYogaCard): 16dp corners, 1dp borderRule, a leading tile or
 * thumbnail, two lines of text and a trailing chevron or action pill. Kinds the
 * design never puts in slot 2 get a sensible variant in the same style.
 */

type IconName = ComponentProps<typeof MaterialIcons>['name'];

interface Props {
  slot: HeroSlot;
  onPress: (slot: HeroSlot) => void;
}

export function HeroSecondaryCard({ slot, onPress }: Props) {
  const press = () => onPress(slot);
  switch (slot.kind) {
    case 'MY_RACE':
      return (
        <Row
          padding={14}
          onPress={press}
          leading={<IconTile icon="directions-run" tone="CORAL" />}
          title={slot.title}
          subtitle={raceLine(slot)}
          trailing={<Chevron />}
        />
      );

    case 'SELL_MARATHON': {
      const date = formatRaceDate(slot.startsAt);
      const from = slot.fromPricePaise > 0 ? `From ${formatPaise(slot.fromPricePaise)}` : null;
      return (
        <Row
          padding={12}
          onPress={press}
          leading={<Thumb uri={slot.imageUrl} icon="directions-run" />}
          title={slot.title}
          subtitle={[date, from].filter(Boolean).join(' · ') || slot.subtitle}
          trailing={<TagPill label="Register" tone="CORAL" />}
        />
      );
    }

    case 'SELL_YOGA':
      // The server's SELL_YOGA copy is written for the full slot-1 banner
      // ("Eight live yoga classes\nevery single day"); as the runner-up the
      // design pitches yoga as an add-on to running, in its own words.
      return (
        <Row
          padding={12}
          onPress={press}
          leading={<Thumb uri={slot.imageUrl} icon="self-improvement" />}
          title="Add Yoga to your running"
          subtitle="Mobility and recovery sessions, 8 daily batches"
          trailing={<TagPill label="Explore" tone="SAGE" />}
        />
      );

    case 'RACE_RESULT':
      return (
        <Row
          padding={14}
          onPress={press}
          leading={<IconTile icon="emoji-events" tone="GOLD" />}
          title={slot.title}
          subtitle={slot.subtitle}
          trailing={<Chevron />}
        />
      );

    case 'YOGA_SESSION':
      return <YogaSessionRow slot={slot} onPress={press} />;

    case 'YOGA_RENEW':
      return (
        <Row
          padding={14}
          onPress={press}
          leading={<IconTile icon="self-improvement" tone="PLUM" />}
          title={slot.title}
          subtitle={slot.subtitle}
          trailing={<TagPill label="Renew" tone="PLUM" />}
        />
      );

    default:
      return null;
  }
}

/** "24 days to go · Bib #DEL-8892A" — the bib half drops out until one is issued. */
function raceLine(slot: HeroMyRace): string {
  const when = slot.isRaceDay
    ? 'Race day'
    : slot.daysRemaining === 1
      ? '1 day to go'
      : `${slot.daysRemaining} days to go`;
  return slot.bibNumber ? `${when} · Bib #${slot.bibNumber}` : when;
}

function YogaSessionRow({ slot, onPress }: { slot: HeroYogaSession; onPress: () => void }) {
  const ticking = useCountdown(slot.state === 'STARTING_SOON' ? slot.startsAt : null);
  const seconds = ticking !== null && Number.isFinite(ticking) ? ticking : slot.secondsToStart;
  const live = slot.state === 'LIVE' || (slot.state === 'STARTING_SOON' && seconds === 0);
  const when = live
    ? 'Live now'
    : slot.state === 'STARTING_SOON'
      ? `Starts in ${Math.max(1, Math.ceil((seconds ?? 0) / 60))} min`
      : formatDayAndTime(slot.startsAt);
  const subtitle = slot.instructorName ? `${when} · ${slot.instructorName}` : when;
  return (
    <Row
      padding={14}
      onPress={onPress}
      leading={<IconTile icon="self-improvement" tone="PLUM" />}
      title={slot.title}
      subtitle={subtitle}
      trailing={live ? <TagPill label="Live" tone="LIVE" /> : <Chevron />}
    />
  );
}

// ── Building blocks ────────────────────────────────────────────────────────

function Row({
  padding,
  onPress,
  leading,
  title,
  subtitle,
  trailing,
}: {
  padding: number;
  onPress: () => void;
  leading: ReactNode;
  title: string;
  subtitle: string | null;
  trailing: ReactNode;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={[styles.card, { padding }]}
      accessibilityRole="button"
      accessibilityLabel={subtitle ? `${title}. ${subtitle}` : title}
    >
      <View style={styles.lead}>
        {leading}
        <View style={styles.text}>
          <Text style={styles.title} numberOfLines={1}>
            {title}
          </Text>
          {subtitle ? (
            <Text style={styles.subtitle} numberOfLines={1}>
              {subtitle}
            </Text>
          ) : null}
        </View>
      </View>
      {trailing}
    </Pressable>
  );
}

/** SecondaryRaceCard's 42dp tinted tile with a brand glyph. */
function IconTile({ icon, tone }: { icon: IconName; tone: 'CORAL' | 'GOLD' | 'PLUM' }) {
  const t = tints[tone];
  return (
    <View style={[styles.tile, { backgroundColor: t.bg }]}>
      <MaterialIcons name={icon} size={24} color={t.fg} />
    </View>
  );
}

/** The sell cards' 52dp photo thumbnail; a glyph stands in when there is no photo. */
function Thumb({ uri, icon }: { uri: string | null; icon: IconName }) {
  return (
    <View style={styles.thumb}>
      <CardImage uri={uri} scrim={{ colors: ['transparent', 'rgba(0,0,0,0.5)'] }} />
      {uri ? null : <MaterialIcons name={icon} size={24} color="rgba(255,255,255,0.85)" />}
    </View>
  );
}

function Chevron() {
  return <MaterialIcons name="chevron-right" size={24} color={colors.coralBrand} />;
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    // Keeps a long event name off the trailing pill; the design's weighted
    // column has no explicit gap.
    gap: 8,
    backgroundColor: colors.paperWhite,
    borderRadius: radii.lg,
    borderWidth: 1,
    borderColor: colors.borderRule,
  },
  lead: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 12 },
  text: { flex: 1 },
  title: {
    fontFamily: FONTS.sans,
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  subtitle: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },
  tile: {
    width: 42,
    height: 42,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  thumb: {
    width: 52,
    height: 52,
    borderRadius: radii.md,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
});
