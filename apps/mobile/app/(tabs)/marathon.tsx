import { useEffect, useState, type ReactNode } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Linking,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import * as Location from 'expo-location';
import * as SecureStore from 'expo-secure-store';
import { useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { MarathonEvent, RaceLifecycleStatus } from '@th/types';
import {
  useContent,
  useMarathonEvents,
  useRaceDetail,
  useReferral,
  useSession,
  useWorkshops,
} from '@/api/hooks';
import { ArticleCard, CardImage, QuoteCard } from '@/components/feed/Cards';
import { ReferAndWinCard } from '@/components/ReferAndWinCard';
import { RunTrackerPanel } from '@/components/RunTrackerPanel';
import { TagPill } from '@/components/TagPill';
import { WorkshopSection } from '@/components/WorkshopSection';
import { FONTS, colors, layout, radii } from '@/theme';
import { formatPaise } from '@/lib/time';
import { ErrorState } from '@/components/QueryState';

/**
 * MARATHON TAB — PRD §8, design MarathonScreenKt.
 *
 * §8.1: two header tabs, present for EVERY user, free and paid, with identical
 * structure. The run tracker is a core feature, not a marathon accessory.
 *
 * The TopHeader brand bar is drawn by the tabs layout above this screen (with
 * the status-bar inset), so the root here is a plain View — no safe-area top
 * edge, no brand row of our own. Below it the design's own title + segment
 * control are the first list item, so they scroll away with the races.
 */

const GUTTER = layout.screenGutter;
const MONO = Platform.select({ ios: 'Menlo', android: 'monospace', default: 'monospace' });

/** §8.2 "never ask twice": set the moment we first raise the system dialog. */
const LOCATION_ASKED_KEY = 'th_marathon_location_asked';

/** Roughly the longest domestic trip; anything further means the user is abroad. */
const MAX_SHOWN_DISTANCE_KM = 3000;

type Tab = 'RACES' | 'RUN_TRACKER';

export default function MarathonTab() {
  const [tab, setTab] = useState<Tab>('RACES');
  const header = <MarathonHeader tab={tab} onChange={setTab} />;

  return (
    <View style={styles.root}>
      {tab === 'RACES' ? (
        <RacesView header={header} />
      ) : (
        // The panel owns its scroll, so here the header stays put above it.
        <>
          {header}
          <RunTrackerPanel />
        </>
      )}
    </View>
  );
}

/** Design lambda$10: "TimesHealth+ Marathon" 24 Bold Serif, padding 18×8, Spacer 12, segment. */
function MarathonHeader({ tab, onChange }: { tab: Tab; onChange: (t: Tab) => void }) {
  return (
    <View style={styles.header}>
      <Text style={styles.title} accessibilityRole="header">
        TimesHealth+ Marathon
      </Text>
      <View style={styles.segment}>
        {(['RACES', 'RUN_TRACKER'] as const).map((t) => {
          const on = tab === t;
          return (
            <Pressable
              key={t}
              style={[styles.segmentItem, on && styles.segmentItemOn]}
              onPress={() => onChange(t)}
              accessibilityRole="tab"
              accessibilityState={{ selected: on }}
            >
              <Text style={[styles.segmentText, on && styles.segmentTextOn]}>
                {t === 'RACES' ? 'Races' : 'Run Tracker'}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

/** ~1 km. Plenty to order editions by city, and a stable cache key (§8.2, docs/04 privacy). */
const roundCoord = (n: number) => Math.round(n * 100) / 100;

/**
 * §8.2: order by nearest edition if location is granted, else fall back to
 * date order SILENTLY. Never block, never ask twice.
 *
 * "Twice" is across launches, not just within one: the first time we raise
 * the system dialog we remember it, whatever the answer, and never raise it
 * again. We still READ the current permission every time, so a user who later
 * grants it in system settings gets nearest-first ordering without a prompt.
 */
function useCoarseLocation() {
  const [coords, setCoords] = useState<{ lat: number; lng: number } | undefined>();

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        let perm = await Location.getForegroundPermissionsAsync();
        if (!perm.granted) {
          if (!perm.canAskAgain) return;
          // Unreadable storage counts as "asked": the rule is never twice.
          const asked = await SecureStore.getItemAsync(LOCATION_ASKED_KEY).catch(() => 'unknown');
          if (asked) return;
          const ask = Location.requestForegroundPermissionsAsync();
          // Recorded as soon as the dialog is up, so even a kill mid-dialog counts.
          await SecureStore.setItemAsync(LOCATION_ASKED_KEY, '1').catch(() => undefined);
          perm = await ask;
          if (!perm.granted) return;
        }
        if (cancelled) return;
        // City-level is all the ordering needs: a cached fix from today is
        // plenty. A fresh phone may have none, so fall back to one quick,
        // low-accuracy reading — and give up quietly if that is slow too.
        const pos =
          (await Location.getLastKnownPositionAsync({ maxAge: 24 * 3600_000 })) ??
          (await Promise.race([
            // No Google "Location Accuracy" dialog: race ordering is a nicety
            // with a silent date-order fallback (§8.2), not worth a system nag
            // on every visit to the tab.
            Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Low, mayShowUserSettingsDialog: false }),
            new Promise<null>((resolve) => setTimeout(() => resolve(null), 8000)),
          ]));
        if (pos && !cancelled) {
          setCoords({ lat: roundCoord(pos.coords.latitude), lng: roundCoord(pos.coords.longitude) });
        }
      } catch {
        // Silent fallback to date ordering.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return coords;
}

function RacesView({ header }: { header: ReactNode }) {
  const router = useRouter();
  const { height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const { data: session } = useSession();
  const coords = useCoarseLocation();
  const { data, isLoading, isError, error, refetch } = useMarathonEvents(coords);
  const { data: workshops } = useWorkshops();
  const { data: content } = useContent();
  const { data: referral } = useReferral(session?.persona.hasMarathon ?? false);

  if (isError && !data) {
    return (
      <>
        {header}
        <ErrorState error={error} onRetry={() => void refetch()} />
      </>
    );
  }
  if (isLoading || !data) {
    return (
      <>
        {header}
        <View style={styles.center}>
          <ActivityIndicator color={colors.coralBrand} />
        </View>
      </>
    );
  }

  // §8.2/§8.3: the user's own races first. Ones still ahead lead the page;
  // ones already run sit in their own section — a finished race is never
  // presented as an "other upcoming edition".
  const mine = data.events.filter((e) => e.registration);
  const upcoming = mine.filter((e) => e.registration?.status !== 'COMPLETED');
  const past = mine.filter((e) => e.registration?.status === 'COMPLETED');
  const others = data.events.filter((e) => !e.registration);
  const [myNext, ...myLater] = upcoming;

  // With no race ahead, the nearest open edition is sold large at the top (§8.5).
  const hero = myNext ? undefined : others[0];
  const rest = hero ? others.slice(1) : others;

  // §8.2: for a free user the top box is "roughly 80% of first screen". The
  // first screen is the window minus the status bar, TopHeader (~64), this
  // tab's title + segment (~102) and the tab bar (64) — not the whole window.
  const firstScreen = Math.max(320, height - insets.top - insets.bottom - 230);
  const heroHeight = mine.length
    ? undefined
    : Math.round(firstScreen * layout.primaryRaceBoxScreenFraction);

  // §8.4: once the race is run, the box's job is "Check Result" — so it opens
  // the result, not the registration detail.
  const openRace = (e: MarathonEvent, distance?: string) =>
    e.registration?.status === 'COMPLETED'
      ? router.push({ pathname: '/race/[eventId]/results', params: { eventId: e.id } })
      : router.push({
          pathname: '/race/[eventId]',
          params: distance ? { eventId: e.id, distance } : { eventId: e.id },
        });
  const openBib = (e: MarathonEvent) =>
    router.push({ pathname: '/bib/[eventId]', params: { eventId: e.id } });

  // §8.5: testimonials and editorial are sales material — shown while there is
  // no race ahead of the user, deliberately below every race box.
  const selling = !myNext;
  // Refer & Win's free upgrade can only land on an upcoming CLASSIC entry —
  // sending the user to a race that is already Premium would be a dead end.
  const claimTarget = upcoming.find(
    (e) => e.registration?.status === 'UPCOMING' && e.registration.tier === 'CLASSIC',
  );

  return (
    <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
      {header}

      {myNext ? (
        <View style={styles.boxItem}>
          <RegisteredRaceBox
            event={myNext}
            onOpen={() => openRace(myNext)}
            onBib={() => openBib(myNext)}
          />
        </View>
      ) : null}
      {/* Registered for 2+ editions → each gets its own box, nearest first (§8.3). */}
      {myLater.map((e) => (
        <View key={e.id} style={styles.rowItem}>
          <RaceRow event={e} onOpen={() => openRace(e)} onAction={() => openBib(e)} />
        </View>
      ))}

      {/* §8.3: Refer & Win as its own visible element in the tab, not buried
          in the detail screen. Shown to registrants — the reward is an upgrade
          to their own entry, so only while that race is still ahead. */}
      {myNext?.registration?.status === 'UPCOMING' && referral ? (
        <View style={styles.referItem}>
          <ReferAndWinCard
            referral={referral}
            eventName={myNext.name}
            onOpenRace={claimTarget ? () => openRace(claimTarget) : undefined}
          />
        </View>
      ) : null}

      {past.length ? (
        <>
          <SectionHeader title="Your past races" />
          {past.map((e) => (
            <View key={e.id} style={styles.boxItem}>
              <RegisteredRaceBox event={e} onOpen={() => openRace(e)} onBib={() => openBib(e)} />
            </View>
          ))}
        </>
      ) : null}

      {hero ? (
        <View style={styles.boxItem}>
          <FreePrimaryRaceHero
            event={hero}
            minHeight={heroHeight}
            onOpen={(distance) => openRace(hero, distance)}
          />
        </View>
      ) : null}

      {rest.length ? (
        // Design: "Other upcoming editions" under a registrant's box, "Upcoming
        // editions across India" for a free user — and only over races they
        // have NOT entered.
        <SectionHeader title={mine.length ? 'Other upcoming editions' : 'Upcoming editions across India'} />
      ) : null}
      {rest.map((e) => (
        <View key={e.id} style={styles.rowItem}>
          <RaceRow event={e} onOpen={() => openRace(e)} onAction={() => openRace(e)} />
        </View>
      ))}

      {/*
        §8.5: for a free user, testimonials and editorial sit deliberately LOW —
        below all four race boxes. Someone who has scrolled this far is not yet
        convinced, and this is the material that convinces. Design order:
        quotes, then articles, then workshops.
      */}
      {selling && content?.quotes.length ? (
        <View style={styles.railBlock}>
          <SectionHeader title="From last year’s runners" />
          <FlatList
            horizontal
            data={content.quotes}
            keyExtractor={(q) => q.id}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.rail}
            renderItem={({ item }) => <QuoteCard quote={item} />}
          />
        </View>
      ) : null}
      {selling && content?.articles.length ? (
        <View style={styles.railBlock}>
          <SectionHeader title="Read before your race" />
          <FlatList
            horizontal
            data={content.articles}
            keyExtractor={(a) => a.id}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.rail}
            renderItem={({ item }) => (
              <ArticleCard article={item} onPress={() => void Linking.openURL(item.url)} />
            )}
          />
        </View>
      ) : null}

      {workshops?.workshops.length ? (
        <View style={styles.workshops}>
          <WorkshopSection workshops={workshops.workshops} initialFilter="MARATHON" />
        </View>
      ) : null}
    </ScrollView>
  );
}

/** CommonComponentsKt.SectionHeader: serif 18 SemiBold, padding 18×12. */
function SectionHeader({ title }: { title: string }) {
  return (
    <Text style={styles.sectionHeader} accessibilityRole="header">
      {title}
    </Text>
  );
}

// ── Shared bits ─────────────────────────────────────────────────────────────

const shortDate = (iso: string) =>
  new Date(iso).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });

/** Cheapest Classic entry across the open distances — the "From ₹" price. */
function fromPaise(event: MarathonEvent): number {
  const prices = event.distanceOptions.map((d) => d.pricePaise.CLASSIC).filter((p) => p > 0);
  return prices.length ? Math.min(...prices) : 0;
}

/** Within India the distance helps pick an edition; from abroad ("12404 km away") it is noise. */
function kmAway(event: MarathonEvent): string | null {
  const km = event.distanceFromUserKm;
  if (km === null || km > MAX_SHOWN_DISTANCE_KM) return null;
  return `${Math.round(km).toLocaleString('en-IN')} km away`;
}

const STATUS_PILL: Record<RaceLifecycleStatus, { label: string; tone: 'CORAL' | 'LIVE' }> = {
  UPCOMING: { label: 'Confirmed entry', tone: 'CORAL' },
  RACE_DAY: { label: 'Race day', tone: 'LIVE' },
  COMPLETED: { label: 'Race completed', tone: 'CORAL' },
};

// ── Registered box — design RegisteredRaceBox ───────────────────────────────

/**
 * RCS 20 white card: a 115dp photo header (fallback #2C1338→#121417, scrim
 * .25→.80) carrying the status pill and the mono REF chip, then a padded-18
 * body. Ahead of the race: "View Digital Bib" + "Race Details". After it: the
 * plum finish-time panel and "Check Result & Certificate" (§8.4).
 */
function RegisteredRaceBox({
  event,
  onOpen,
  onBib,
}: {
  event: MarathonEvent;
  onOpen: () => void;
  onBib: () => void;
}) {
  const reg = event.registration!;
  const pill = STATUS_PILL[reg.status];
  const completed = reg.status === 'COMPLETED';

  return (
    <Pressable style={styles.card20} onPress={onOpen} accessibilityRole="button">
      <View style={styles.regImage}>
        <CardImage
          uri={event.imageUrl}
          fallback={{ colors: ['#2C1338', colors.carbon950], dir: 'vertical' }}
          scrim={{ colors: ['rgba(0,0,0,0.25)', 'rgba(0,0,0,0.8)'], dir: 'vertical' }}
        />
        <View style={styles.regImageRow}>
          <View style={styles.pillRow}>
            <TagPill label={pill.label} tone={pill.tone} />
            {/* §8.3 edge case: a rescheduled edition says so on the box itself. */}
            {event.rescheduledFrom ? <TagPill label="Rescheduled" tone="GOLD" /> : null}
          </View>
          <Text style={styles.refChip} numberOfLines={1}>
            REF: {reg.bibNumber ?? reg.registrationRef}
          </Text>
        </View>
      </View>

      <View style={styles.regBody}>
        <Text style={styles.regName} numberOfLines={2}>
          {event.name}
        </Text>
        <Text style={styles.regMeta}>
          {reg.category} · {shortDate(event.startsAt)} · Flag-off: {event.flagOffTime}
        </Text>

        {completed ? (
          <>
            <FinishPanel eventId={event.id} />
            <Pressable style={styles.resultBtn} onPress={onOpen} accessibilityRole="button">
              <Text style={styles.btnLabel13}>Check Result & Certificate</Text>
            </Pressable>
          </>
        ) : (
          <View style={styles.regActions}>
            <Pressable style={styles.bibBtn} onPress={onBib} accessibilityRole="button">
              <Text style={[styles.btnLabel125, { color: colors.paperWhite }]}>View Digital Bib</Text>
            </Pressable>
            <Pressable style={styles.outlineBtn} onPress={onOpen} accessibilityRole="button">
              <Text style={[styles.btnLabel125, { color: colors.textPrimary }]}>Race Details</Text>
            </Pressable>
          </View>
        )}
      </View>
    </Pressable>
  );
}

/**
 * The PlumTint "OFFICIAL FINISH TIME" panel. The race list carries no result,
 * so the box reads the race detail (the same query the result screen uses,
 * so opening it is instant). §8.4: unpublished results are a pending STATE on
 * the box, never an empty result.
 */
function FinishPanel({ eventId }: { eventId: string }) {
  const { data } = useRaceDetail(eventId);
  const result = data?.result;
  const published = Boolean(result?.published);

  return (
    <View style={styles.finishPanel}>
      <View style={{ flexShrink: 1 }}>
        <Text style={styles.finishLabel}>OFFICIAL FINISH TIME</Text>
        {published ? (
          <Text style={styles.finishTime}>{result?.chipTime ?? result?.finishTime ?? '—'}</Text>
        ) : (
          <Text style={styles.finishPending}>{data ? 'Being published' : ' '}</Text>
        )}
      </View>
      {published && result?.medalStatus ? (
        <TagPill label={`Medal: ${result.medalStatus}`} tone="GOLD" />
      ) : data && !published ? (
        <TagPill label="Results pending" tone="NEUTRAL" />
      ) : null}
    </View>
  );
}

// ── Free primary box — design FreePrimaryRaceHero ───────────────────────────

/**
 * RCS 22 white outlined card: a photo header (fallback #1E284A→#11172E, scrim
 * .35→.82) then "CHOOSE YOUR DISTANCE" chips and a "From ₹ … Register" footer.
 * §8.2 sizes it to ~80% of the first screen for a free user; the photo header
 * takes up the extra height so the body keeps the design's proportions.
 */
function FreePrimaryRaceHero({
  event,
  minHeight,
  onOpen,
}: {
  event: MarathonEvent;
  minHeight?: number;
  onOpen: (distance?: string) => void;
}) {
  const options = event.distanceOptions;
  const [picked, setPicked] = useState<string | undefined>(
    () => (options.find((d) => d.code === '21K') ?? options[options.length - 1])?.code,
  );
  const selected = options.find((d) => d.code === picked);
  const open = event.registrationOpen && (selected?.registrationOpen ?? true);
  const price = selected?.pricePaise.CLASSIC || fromPaise(event);
  const away = kmAway(event);
  const year = new Date(event.startsAt).getFullYear();

  return (
    <Pressable
      style={[styles.card22, minHeight ? { minHeight } : null]}
      onPress={() => onOpen(picked)}
      accessibilityRole="button"
    >
      <View style={styles.heroImage}>
        <CardImage
          uri={event.imageUrl}
          fallback={{ colors: ['#1E284A', '#11172E'] }}
          scrim={{ colors: ['rgba(0,0,0,0.35)', 'rgba(0,0,0,0.82)'], dir: 'vertical' }}
        />
        <View style={styles.heroImageContent}>
          <View style={styles.heroTopRow}>
            <View style={styles.pillRow}>
              {/* §8.3 edge case: registration closed → closed state, no register action. */}
              {event.registrationOpen ? (
                <TagPill label={`Registrations open · ${year}`} tone="CORAL" />
              ) : (
                <TagPill label="Registrations closed" tone="NEUTRAL" />
              )}
              {event.rescheduledFrom ? <TagPill label="Rescheduled" tone="GOLD" /> : null}
            </View>
            {away ? <Text style={styles.awayChip}>{away.toUpperCase()}</Text> : null}
          </View>
          <View>
            <Text style={styles.heroName} numberOfLines={2}>
              {event.name}
            </Text>
            <Text style={styles.heroMeta} numberOfLines={2}>
              {event.city} · {shortDate(event.startsAt)} · {event.venue}
            </Text>
          </View>
        </View>
      </View>

      <View style={styles.heroBody}>
        <Text style={styles.eyebrow}>CHOOSE YOUR DISTANCE</Text>
        <View style={styles.chipRow}>
          {options.map((d) => {
            const on = d.code === picked;
            return (
              <Pressable
                key={d.code}
                style={[styles.distChip, on && styles.distChipOn]}
                onPress={() => setPicked(d.code)}
                accessibilityRole="radio"
                accessibilityState={{ selected: on }}
              >
                <Text style={[styles.distChipText, on && { color: colors.coralBrand }]}>{d.code}</Text>
              </Pressable>
            );
          })}
        </View>

        <View style={styles.heroFooter}>
          <View style={{ flexShrink: 1 }}>
            <Text style={styles.heroPrice}>From {formatPaise(price)}</Text>
            <Text style={styles.heroPriceNote}>Classic Entry with chip & t-shirt</Text>
          </View>
          {open ? (
            <Pressable
              style={styles.registerBtn}
              onPress={() => onOpen(picked)}
              accessibilityRole="button"
            >
              <Text style={styles.btnLabel13}>Register</Text>
            </Pressable>
          ) : (
            <TagPill
              label={event.registrationOpen ? `${picked ?? 'Distance'} closed` : 'Entries closed'}
              tone="NEUTRAL"
            />
          )}
        </View>
      </View>
    </Pressable>
  );
}

// ── Compact row — design OtherRaceEditionCard ───────────────────────────────

/**
 * RCS 16 white outlined row, padding 12: a 62dp R12 thumbnail (fallback
 * coral → #8A1E14), serif 14.5 name, date + distances, coral "From ₹", and a
 * SurfaceSand 38dp "Register" button. A second race the user has entered uses
 * the same row with its status pill and a "View Bib" button instead.
 */
function RaceRow({
  event,
  onOpen,
  onAction,
}: {
  event: MarathonEvent;
  onOpen: () => void;
  onAction: () => void;
}) {
  const reg = event.registration;
  const away = kmAway(event);
  const pill = reg ? STATUS_PILL[reg.status] : null;

  return (
    <Pressable style={styles.rowCard} onPress={onOpen} accessibilityRole="button">
      <View style={styles.rowLeft}>
        <View style={styles.thumb}>
          <CardImage uri={event.imageUrl} fallback={{ colors: [colors.coralBrand, '#8A1E14'] }} />
        </View>
        <View style={{ flex: 1 }}>
          {pill || event.rescheduledFrom ? (
            <View style={[styles.pillRow, { marginBottom: 4 }]}>
              {pill ? <TagPill label={pill.label} tone={pill.tone} /> : null}
              {event.rescheduledFrom ? <TagPill label="Rescheduled" tone="GOLD" /> : null}
            </View>
          ) : null}
          <Text style={styles.rowName} numberOfLines={2}>
            {event.name}
          </Text>
          <Text style={styles.rowMeta} numberOfLines={1}>
            {reg
              ? `${reg.category} · ${shortDate(event.startsAt)}`
              : `${shortDate(event.startsAt)} · ${event.distanceOptions.map((d) => d.code).join(' / ')}`}
          </Text>
          {reg ? (
            <Text style={styles.rowMeta} numberOfLines={1}>
              Flag-off: {event.flagOffTime}
            </Text>
          ) : (
            <Text style={styles.rowPrice} numberOfLines={1}>
              {event.registrationOpen ? `From ${formatPaise(fromPaise(event))}` : 'Registrations closed'}
              {away ? <Text style={styles.rowAway}>{`  ·  ${away}`}</Text> : null}
            </Text>
          )}
        </View>
      </View>
      {/* §8.3: a closed edition shows a closed state, never a register action. */}
      {reg || event.registrationOpen ? (
        <Pressable style={styles.rowBtn} onPress={onAction} accessibilityRole="button">
          <Text style={styles.rowBtnText}>{reg ? 'View Bib' : 'Register'}</Text>
        </Pressable>
      ) : (
        <TagPill label="Closed" tone="NEUTRAL" />
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  // Design LazyColumn contentPadding(bottom = 90); every item carries its own 18 gutter.
  scroll: { paddingBottom: 90 },

  header: { paddingHorizontal: GUTTER, paddingVertical: 8 },
  title: {
    fontFamily: FONTS.serif,
    fontSize: 24,
    lineHeight: 30,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  segment: {
    marginTop: 12,
    flexDirection: 'row',
    backgroundColor: colors.surfaceSand,
    borderRadius: radii.md,
    padding: 4,
  },
  segmentItem: { flex: 1, alignItems: 'center', paddingVertical: 8, borderRadius: 10 },
  segmentItemOn: { backgroundColor: colors.paperWhite },
  segmentText: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '500', color: colors.textSecondary },
  segmentTextOn: { fontWeight: '700', color: colors.coralBrand },

  sectionHeader: {
    paddingHorizontal: GUTTER,
    paddingVertical: 12,
    fontFamily: FONTS.serif,
    fontSize: 18,
    lineHeight: 23,
    fontWeight: '600',
    color: colors.textPrimary,
  },

  // Item wrappers — design paddings per item.
  boxItem: { paddingHorizontal: GUTTER, paddingVertical: 6 },
  referItem: { paddingHorizontal: GUTTER, paddingVertical: 8 },
  rowItem: { paddingHorizontal: GUTTER, paddingVertical: 5 },
  railBlock: { marginTop: 14 },
  rail: { paddingHorizontal: GUTTER, gap: 12 },
  workshops: { marginTop: 16 },

  pillRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, flexShrink: 1 },

  // Registered box
  card20: {
    borderRadius: radii.xl,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    overflow: 'hidden',
  },
  regImage: { height: 115 },
  regImageRow: {
    padding: 14,
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: 8,
  },
  refChip: {
    backgroundColor: 'rgba(0,0,0,0.5)',
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
    overflow: 'hidden',
    fontFamily: MONO,
    fontSize: 11,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  regBody: { padding: 18 },
  regName: {
    fontFamily: FONTS.serif,
    fontSize: 24,
    lineHeight: 29,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  regMeta: {
    marginTop: 2,
    marginBottom: 14,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 16,
    color: colors.textSecondary,
  },
  regActions: { flexDirection: 'row', gap: 8 },
  bibBtn: {
    flex: 1,
    height: 46,
    borderRadius: radii.md,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  outlineBtn: {
    flex: 1,
    height: 46,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnLabel125: { fontFamily: FONTS.sans, fontSize: 12.5, fontWeight: '700' },
  // M3 Button default label (labelLarge): 13 Bold.
  btnLabel13: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.paperWhite },
  finishPanel: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    backgroundColor: colors.plumTint,
    borderRadius: radii.md,
    padding: 12,
  },
  finishLabel: {
    fontFamily: FONTS.sans,
    fontSize: 9.5,
    fontWeight: '800',
    color: colors.plumBrand,
  },
  finishTime: {
    fontFamily: MONO,
    fontSize: 22,
    lineHeight: 28,
    fontWeight: '700',
    color: colors.plumDeep,
  },
  finishPending: {
    fontFamily: FONTS.serif,
    fontSize: 17,
    lineHeight: 28,
    fontWeight: '700',
    color: colors.plumDeep,
  },
  resultBtn: {
    marginTop: 12,
    height: 48,
    borderRadius: radii.md,
    backgroundColor: colors.carbon900,
    alignItems: 'center',
    justifyContent: 'center',
  },

  // Free primary box
  card22: {
    borderRadius: radii.hero,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    overflow: 'hidden',
  },
  heroImage: { flexGrow: 1, minHeight: 150 },
  heroImageContent: { flexGrow: 1, padding: 16, justifyContent: 'space-between', gap: 12 },
  heroTopRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: 8,
  },
  awayChip: {
    backgroundColor: 'rgba(0,0,0,0.5)',
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
    overflow: 'hidden',
    fontFamily: FONTS.sans,
    fontSize: 9,
    fontWeight: '800',
    letterSpacing: 0.6,
    color: colors.paperWhite,
  },
  heroName: {
    fontFamily: FONTS.serif,
    fontSize: 24,
    lineHeight: 29,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  heroMeta: {
    marginTop: 2,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 16,
    color: 'rgba(255,255,255,0.8)',
  },
  heroBody: { padding: 16 },
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
  heroFooter: {
    marginTop: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
  },
  heroPrice: {
    fontFamily: FONTS.serif,
    fontSize: 22,
    lineHeight: 27,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  heroPriceNote: { fontFamily: FONTS.sans, fontSize: 11, lineHeight: 15, color: colors.textMuted },
  registerBtn: {
    height: 44,
    borderRadius: 10,
    paddingHorizontal: 24,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },

  // Compact row
  rowCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    padding: 12,
    borderRadius: radii.lg,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
  },
  rowLeft: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 12 },
  thumb: { width: 62, height: 62, borderRadius: radii.md, overflow: 'hidden' },
  rowName: {
    fontFamily: FONTS.serif,
    fontSize: 14.5,
    lineHeight: 19,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  rowMeta: { fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: 15, color: colors.textMuted },
  rowPrice: {
    marginTop: 2,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 16,
    fontWeight: '700',
    color: colors.coralBrand,
  },
  rowAway: { fontWeight: '400', color: colors.textMuted },
  rowBtn: {
    height: 38,
    borderRadius: radii.sm,
    paddingHorizontal: 14,
    backgroundColor: colors.surfaceSand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  rowBtnText: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.textPrimary },
});
