import { useState, type ReactElement, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import type { YogaBatch, YogaCategory, YogaSession } from '@th/types';
import {
  useContent,
  useSession,
  useSetReminderSlot,
  useWorkshops,
  useYogaAttendance,
  useYogaCatalog,
  useYogaToday,
} from '@/api/hooks';
import { CardImage, ReelCard, VideoCard } from '@/components/feed/Cards';
import { TagPill } from '@/components/TagPill';
import { WorkshopSection } from '@/components/WorkshopSection';
import {
  FaqAccordion,
  InstituteCard,
  InstructorAvatar,
  InstructorsStrip,
  MONO,
  MemberQuotes,
  MentorCard,
  SectionHeader,
  SupportCard,
} from '@/components/YogaAbout';
import { FONTS, colors, layout, radii } from '@/theme';
import { formatCountdown, formatDayAndTime, serverNow, useCountdown, useServerNow } from '@/lib/time';
import { ErrorState } from '@/components/QueryState';
import { isJoinOpen, useJoinClass } from '@/lib/joinClass';

/**
 * YOGA TAB — PRD §7, drawn to the design's YogaScreenKt.
 *
 * Subscriber (§7.1): Sessions and Tracker under one "TimesHealth+ Yoga" title.
 * Free user (§7.2): a single scrolling sales page with content in it. No
 * Sessions/Tracker switch at all, and the Tracker does not exist — this is
 * deliberately NOT a crippled version of the subscriber tab.
 *
 * The brand bar (TopHeader) is the Tabs header and owns the status-bar inset,
 * so nothing here adds a top safe-area edge or a second app bar. As in the
 * design, the title and segment are the first list item and scroll away.
 */

const GUTTER = layout.screenGutter;

/** YogaScreenKt.NextSessionHero's own photo — a YogaBatch carries no image. */
const NEXT_SESSION_PHOTO =
  'https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?auto=format&fit=crop&w=800&q=80';

/** The design prints clock times as "6:30 AM"; en-IN formats them "6:30 am". */
const dayClock = (iso: string) =>
  formatDayAndTime(iso).replace(/\b(am|pm)\b/i, (m) => m.toUpperCase());
/** Same, without the "Today · " prefix — the hero's time chip only needs the day when it isn't today. */
const heroClock = (iso: string) => dayClock(iso).replace(/^Today · /, '');

export default function YogaTab() {
  const { data: session } = useSession();
  const subscriber = session?.persona.hasYoga ?? false;
  // Design: YogaFreeContent(z = persona == YOGA_EXPIRED) — a lapsed member is
  // welcomed back rather than pitched to as a stranger.
  const expired = session?.persona.persona === 'YOGA_EXPIRED';
  return subscriber ? <SubscriberYoga /> : <FreeYoga expired={expired} />;
}

// ── Subscriber ──────────────────────────────────────────────────────────────

type Tab = 'SESSIONS' | 'TRACKER';

function SubscriberYoga() {
  const [tab, setTab] = useState<Tab>('SESSIONS');
  const header = <YogaHeader tab={tab} onTab={setTab} />;
  return (
    <View style={styles.root}>
      {tab === 'SESSIONS' ? <SessionsView header={header} /> : <TrackerView header={header} />}
    </View>
  );
}

/** "TimesHealth+ Yoga" (serif 24 Bold) over the sand segment control, padding(18, 8). */
function YogaHeader({ tab, onTab }: { tab: Tab; onTab: (t: Tab) => void }) {
  return (
    <View style={styles.header}>
      <Text style={styles.headerTitle} accessibilityRole="header">
        TimesHealth+ Yoga
      </Text>
      <View style={styles.segment} accessibilityRole="tablist">
        {(['SESSIONS', 'TRACKER'] as const).map((t) => {
          const on = tab === t;
          return (
            <Pressable
              key={t}
              style={[styles.segmentItem, on && styles.segmentItemOn]}
              onPress={() => onTab(t)}
              accessibilityRole="tab"
              accessibilityState={{ selected: on }}
            >
              <Text style={[styles.segmentText, on && styles.segmentTextOn]}>
                {t === 'SESSIONS' ? 'Sessions' : 'Tracker'}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

/** Loading and error keep the title and segment in place, so the user can still switch. */
function WithHeader({ header, children }: { header: ReactElement; children: ReactNode }) {
  return (
    <View style={styles.root}>
      {header}
      <View style={styles.root}>{children}</View>
    </View>
  );
}

function SessionsView({ header }: { header: ReactElement }) {
  const router = useRouter();
  const { data, isLoading, isError, error, refetch } = useYogaToday();
  const { data: catalog } = useYogaCatalog();
  const { data: workshops } = useWorkshops();
  const { data: content } = useContent();
  const { joinClass, joiningBatchId } = useJoinClass();
  const setSlot = useSetReminderSlot();

  const remindMe = (batchId: string) =>
    setSlot.mutate(batchId, {
      onError: () => Alert.alert('Couldn’t update your reminder', 'Check your connection and try again.'),
    });
  const savingSlot = setSlot.isPending ? setSlot.variables : null;
  // The tab can sit open across a class starting or ending: join/live state
  // is worked out against a ticking clock, not frozen at the last render.
  const now = useServerNow(30_000);
  const isLive = (b: YogaBatch) => now >= Date.parse(b.startsAt) && now < Date.parse(b.endsAt);

  if (isError && !data) {
    return (
      <WithHeader header={header}>
        <ErrorState error={error} onRetry={() => void refetch()} />
      </WithHeader>
    );
  }
  if (isLoading || !data) {
    return (
      <WithHeader header={header}>
        <Loading />
      </WithHeader>
    );
  }

  const next = data.batches.find((b) => b.id === (data.liveBatchId ?? data.nextBatchId));
  // The batch list carries TODAY's instants; after the last class the next
  // session is tomorrow's, which the response carries separately.
  const nextStartsAt = next
    ? isLive(next)
      ? next.startsAt
      : (data.nextSessionStartsAt ?? next.startsAt)
    : null;

  const openExplorer = (categoryId?: string) =>
    router.push(
      categoryId ? { pathname: '/yoga-explorer', params: { categoryId } } : { pathname: '/yoga-explorer' },
    );

  const listHeader = (
    <>
      {header}

      {next && nextStartsAt ? (
        <NextSessionHero
          batch={next}
          startsAt={nextStartsAt}
          avatarUrl={content?.instructors.find((i) => i.name === next.instructorName)?.avatarUrl}
          now={now}
          live={isLive(next)}
          joining={joiningBatchId === next.id}
          saving={savingSlot === next.id}
          onJoin={() => joinClass(next.id)}
          onRemind={() => remindMe(next.id)}
        />
      ) : null}

      {catalog?.categories.length ? (
        <AnatomyCard categories={catalog.categories} onOpen={openExplorer} />
      ) : null}

      {/* §7.1: all 8 daily batches, the user's slot marked, any batch joinable. */}
      <SectionHeader title={`All ${data.batches.length} daily batches`} />
      <Text style={styles.note}>
        Pick any slot for reminders. The same live link works for all {data.batches.length} batches.
      </Text>
      {data.batches.map((b) => {
        // The join window (wait room → end of class) already covers "live".
        const open = isJoinOpen(b.startsAt, now);
        return (
          <BatchRowCard
            key={b.id}
            batch={b}
            open={open}
            live={isLive(b)}
            joining={joiningBatchId === b.id}
            saving={savingSlot === b.id}
            // An open class joins (the same window the server counts
            // attendance in); any other row picks that batch for reminders.
            onPress={() => (open ? joinClass(b.id) : remindMe(b.id))}
          />
        );
      })}

      {workshops?.workshops.length ? (
        <View style={styles.spaced14}>
          {/* Design: a subscriber's Yoga tab opens the section on the Yoga filter. */}
          <WorkshopSection workshops={workshops.workshops} initialFilter="YOGA" />
        </View>
      ) : null}

      {catalog?.sessions.length ? (
        // §7.1 "browsable by programme track": Library opens the by-track explorer.
        <SectionHeader
          title="Past session recordings"
          action="Library"
          onAction={() => openExplorer()}
          style={styles.spaced14}
        />
      ) : null}
    </>
  );

  // §7.1 below the fold. The design draws the Institute and the FAQs; the PRD
  // also asks for instructors, the chief mentor and support, which follow them.
  const listFooter = (
    <>
      <InstituteCard />
      {content ? (
        <>
          <FaqAccordion faqs={content.yogaFaqs} />
          <InstructorsStrip instructors={content.instructors} />
          <MentorCard mentor={content.mentor} />
        </>
      ) : null}
      <SupportCard />
    </>
  );

  return (
    // The recordings library is the part that grows, so it is the list's data
    // (virtualised), exactly like the design's LazyColumn items.
    <FlatList
      data={catalog?.sessions ?? []}
      keyExtractor={(s) => s.id}
      renderItem={({ item }) => (
        <RecordingRowCard
          session={item}
          // Design: a recording row plays straight away ("Watch") — a
          // subscriber is entitled to the whole library.
          onPress={() => router.push({ pathname: '/video/[id]', params: { id: item.id } })}
        />
      )}
      ListHeaderComponent={listHeader}
      ListFooterComponent={listFooter}
      contentContainerStyle={styles.list}
      showsVerticalScrollIndicator={false}
    />
  );
}

/**
 * NextSessionHero — photo card, R22 with a plumLine border. Its own component
 * so the per-second countdown re-renders the hero only, not the whole list —
 * and so the Join button appears the moment the wait room opens.
 */
function NextSessionHero({
  batch,
  startsAt,
  avatarUrl,
  now,
  live,
  joining,
  saving,
  onJoin,
  onRemind,
}: {
  batch: YogaBatch;
  startsAt: string;
  avatarUrl?: string;
  now: number;
  live: boolean;
  joining: boolean;
  saving: boolean;
  onJoin: () => void;
  onRemind: () => void;
}) {
  const secs = useCountdown(live ? null : startsAt);
  // Join only while the class link is open (from 1h before until it ends) —
  // the same window the server counts attendance in.
  const joinable = isJoinOpen(startsAt, now);
  const minutes = Math.round((Date.parse(batch.endsAt) - Date.parse(batch.startsAt)) / 60_000);
  const status = live ? 'Live now' : `Next session · ${formatCountdown(secs ?? 0)}`;

  return (
    <View style={styles.heroWrap}>
      <View style={styles.hero}>
        <CardImage
          uri={NEXT_SESSION_PHOTO}
          fallback={{ colors: [colors.plumBrand, colors.plumDeep] }}
          scrim={{ colors: ['rgba(0,0,0,0.35)', 'rgba(0,0,0,0.88)'] }}
        />
        <View style={styles.heroBody}>
          <View style={styles.heroTop}>
            {/* Emerald is reserved for the live state (§6.1), so a class that
                is hours away wears the neutral pill instead. */}
            <TagPill label={status} tone={joinable ? 'LIVE' : 'NEUTRAL'} style={styles.shrink} />
            <View style={styles.timeChip}>
              <Text style={styles.timeChipText}>{heroClock(startsAt)}</Text>
            </View>
          </View>
          <Text style={styles.heroTitle}>{batch.title}</Text>
          <View style={styles.heroMeta}>
            <InstructorAvatar name={batch.instructorName} uri={avatarUrl} size={24} />
            {/* The daily live batches are open to every level (design copy). */}
            <Text style={styles.heroMetaText} numberOfLines={1}>
              {batch.instructorName} · {minutes} min · All levels
            </Text>
          </View>

          {joinable ? (
            <Pressable
              style={[styles.heroBtn, styles.heroBtnJoin]}
              disabled={joining}
              onPress={onJoin}
              accessibilityRole="button"
            >
              <MaterialIcons name="play-arrow" size={16} color={colors.paperWhite} />
              <Text style={styles.heroBtnText}>{joining ? 'Opening…' : 'Join Live Session'}</Text>
            </Pressable>
          ) : batch.isUserReminderSlot ? (
            // Outside the join window the same button carries the reminder
            // state; there is nothing to join yet.
            <View style={[styles.heroBtn, styles.heroBtnQuiet]} accessibilityState={{ disabled: true }}>
              <MaterialIcons name="notifications-active" size={16} color={colors.paperWhite} />
              <Text style={styles.heroBtnText}>Reminder on · Join opens 1h before</Text>
            </View>
          ) : (
            <Pressable
              style={[styles.heroBtn, styles.heroBtnQuiet]}
              disabled={saving}
              onPress={onRemind}
              accessibilityRole="button"
            >
              <MaterialIcons name="notifications-none" size={16} color={colors.paperWhite} />
              <Text style={styles.heroBtnText}>{saving ? 'Saving…' : 'Remind me'}</Text>
            </Pressable>
          )}
        </View>
      </View>
    </View>
  );
}

/** Lambda$28 — the "Targeted Anatomy" card straight under the hero. */
function AnatomyCard({
  categories,
  onOpen,
}: {
  categories: YogaCategory[];
  onOpen: (categoryId?: string) => void;
}) {
  return (
    <View style={styles.anatomy}>
      <View style={styles.rowBetween}>
        <TagPill label="Targeted anatomy" tone="CORAL" />
        <Pressable style={styles.viewAll} onPress={() => onOpen()} hitSlop={10} accessibilityRole="link">
          <Text style={styles.viewAllText}>View all {categories.length}</Text>
          <MaterialIcons name="chevron-right" size={16} color={colors.plumDeep} />
        </Pressable>
      </View>
      <Text style={styles.anatomyTitle}>Explore Sessions by Body Target</Text>
      <Text style={styles.anatomySub}>
        Select your priority to relieve stiffness and improve everyday lifestyle
      </Text>
      <FlatList
        horizontal
        data={categories}
        keyExtractor={(c) => c.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.gap8}
        renderItem={({ item }) => (
          <Pressable
            style={styles.tile}
            onPress={() => onOpen(item.id)}
            accessibilityRole="button"
            accessibilityLabel={`${item.name}, ${item.sessionCount} sessions`}
          >
            <CardImage uri={item.imageUrl} scrim={{ colors: ['rgba(0,0,0,0.2)', 'rgba(0,0,0,0.85)'] }} />
            <View style={styles.tileBody}>
              <View style={styles.tileChip}>
                <Text style={styles.tileChipText}>{item.sessionCount} {item.sessionCount === 1 ? 'SESSION' : 'SESSIONS'}</Text>
              </View>
              <Text style={styles.tileName} numberOfLines={1}>
                {item.name}
              </Text>
            </View>
          </Pressable>
        )}
      />
    </View>
  );
}

/** BatchRowCard — one card per batch; the reminder slot is the plum one. */
function BatchRowCard({
  batch,
  open,
  live,
  joining,
  saving,
  onPress,
}: {
  batch: YogaBatch;
  open: boolean;
  live: boolean;
  joining: boolean;
  saving: boolean;
  onPress: () => void;
}) {
  const slot = batch.isUserReminderSlot;
  const label = joining
    ? 'Opening…'
    : open
      ? 'Join'
      : saving
        ? 'Saving…'
        : slot
          ? 'Reminder on'
          : 'Remind me';
  // Nothing to do on your own slot before its window opens.
  const disabled = joining || saving || (!open && slot);
  return (
    <Pressable
      style={[styles.batch, slot && styles.batchSlot]}
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={`${dayClock(batch.startsAt)}, ${batch.title} with ${batch.instructorName}. ${label}`}
    >
      <View style={styles.flex}>
        {slot || live ? (
          <View style={styles.eyebrowRow}>
            {slot ? <Text style={styles.slotEyebrow}>YOUR REMINDER SLOT</Text> : null}
            {live ? (
              <Text style={[styles.slotEyebrow, { color: colors.liveEmerald }]}>LIVE NOW</Text>
            ) : null}
          </View>
        ) : null}
        <Text style={styles.batchTime}>{heroClock(batch.startsAt)}</Text>
        <Text style={styles.batchMeta} numberOfLines={1}>
          {batch.title} · {batch.instructorName}
        </Text>
      </View>
      <View style={[styles.batchBtn, slot && styles.batchBtnSlot]}>
        <Text style={[styles.batchBtnText, slot && { color: colors.paperWhite }]}>{label}</Text>
      </View>
    </Pressable>
  );
}

/** RecordingRowCard — 72×52 thumb, title, avatar + "instructor · level", "Watch". */
function RecordingRowCard({ session, onPress }: { session: YogaSession; onPress: () => void }) {
  return (
    <Pressable
      style={styles.recording}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`Watch ${session.title}, ${session.durationMinutes} minutes`}
    >
      <View style={styles.recordingLead}>
        <View style={styles.recordingThumb}>
          <CardImage uri={session.imageUrl} scrim={{ colors: ['rgba(0,0,0,0.2)', 'rgba(0,0,0,0.65)'] }} />
          <View style={styles.recordingPlay}>
            <MaterialIcons name="play-arrow" size={14} color={colors.plumDeep} />
          </View>
          <View style={styles.recordingDur}>
            <Text style={styles.recordingDurText}>{session.durationMinutes}m</Text>
          </View>
        </View>
        <View style={styles.flex}>
          <Text style={styles.recordingTitle} numberOfLines={1}>
            {session.title}
          </Text>
          <View style={styles.recordingMeta}>
            <InstructorAvatar name={session.instructor.name} uri={session.instructor.avatarUrl} size={16} />
            <Text style={styles.recordingMetaText} numberOfLines={1}>
              {session.instructor.name} · {session.level}
            </Text>
          </View>
        </View>
      </View>
      <Text style={styles.watch}>Watch</Text>
    </Pressable>
  );
}

// ── Tracker (§7.1) ──────────────────────────────────────────────────────────

function TrackerView({ header }: { header: ReactElement }) {
  const { data, isLoading, isError, error, refetch } = useYogaAttendance(true);
  if (isError && !data) {
    return (
      <WithHeader header={header}>
        <ErrorState error={error} onRetry={() => void refetch()} />
      </WithHeader>
    );
  }
  if (isLoading || !data) {
    return (
      <WithHeader header={header}>
        <Loading />
      </WithHeader>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.list} showsVerticalScrollIndicator={false}>
      {header}
      <View style={styles.tracker}>
        <View style={styles.streak}>
          <TagPill label="Current streak" tone="GOLD" />
          <View style={styles.streakRow}>
            <Text style={styles.streakNum}>{data.currentStreak}</Text>
            <Text style={styles.streakUnit}>{data.currentStreak === 1 ? 'day' : 'days'} in a row</Text>
          </View>
          <Text style={styles.streakNote}>
            Keep it going. One live practice today maintains your streak.
          </Text>
        </View>

        <View style={styles.miniRow}>
          <MiniStatBlock value={`${data.classesAttended}`} label={'Classes\nattended'} />
          <MiniStatBlock value={`${data.currentStreak}`} label={'Current\nstreak'} />
          <MiniStatBlock value={`${data.bestStreak}`} label={'Best\nstreak'} />
          <MiniStatBlock value={`${data.attendanceRate}%`} label={'Attendance\nrate'} />
        </View>

        <MonthCalendar attendedDates={data.attendedDates} trackingSince={data.trackingSince} />

        <View style={styles.sageNote}>
          <Text style={styles.leaf}>🌿</Text>
          <Text style={styles.sageText}>
            Single-source attendance verified: joining from app or WhatsApp synchronizes your streak
            instantly.
          </Text>
        </View>
      </View>

      {/* §7.1 "forward view of scheduled sessions" — not drawn in the design,
          so it borrows the batch card. Read-only: joining happens on Sessions. */}
      {data.upcomingSessions.length ? (
        <>
          <SectionHeader title="Coming up" />
          {data.upcomingSessions.map((s) => (
            <View key={`${s.date}-${s.batchId}`} style={styles.batch}>
              <View style={styles.flex}>
                <Text style={styles.batchTime}>{dayClock(s.startsAt)}</Text>
                <Text style={styles.batchMeta} numberOfLines={1}>
                  {s.title}
                </Text>
              </View>
            </View>
          ))}
        </>
      ) : null}
    </ScrollView>
  );
}

function MiniStatBlock({ value, label }: { value: string; label: string }) {
  return (
    <View style={styles.mini}>
      <Text style={styles.miniValue}>{value}</Text>
      <Text style={styles.miniLabel}>{label}</Text>
    </View>
  );
}

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];
/** Design literals (YogaScreenKt calendar): the "Missed" legend dot and missed cell fill. */
const MISSED_DOT = '#F6E6E9';
const MISSED_BG = '#FCEEEF';
const IST_OFFSET_MS = 330 * 60_000;

type DayState = 'TODAY' | 'FUTURE' | 'ATTENDED' | 'MISSED' | 'NEUTRAL';

const CELL: Record<DayState, { bg: string; fg: string; bold: boolean }> = {
  TODAY: { bg: colors.plumBrand, fg: colors.paperWhite, bold: true },
  ATTENDED: { bg: colors.plumTint, fg: colors.plumBrand, bold: true },
  MISSED: { bg: MISSED_BG, fg: colors.crimsonAlert, bold: true },
  FUTURE: { bg: colors.surfaceSand, fg: colors.textMuted, bold: false },
  // Before the membership began: not attended, but not "missed" either.
  NEUTRAL: { bg: colors.surfaceSand, fg: colors.textMuted, bold: false },
};

/**
 * §7.1 "Month calendar, present/absent".
 *
 * The attendance ledger is keyed by IST date, so the month — and which cell
 * is today — are worked out in IST from the server clock, whatever timezone
 * the phone is set to. Otherwise a member abroad (or a phone on UTC before
 * 5:30 AM) would see yesterday as "today" and a real class as missed.
 */
function MonthCalendar({
  attendedDates,
  trackingSince,
}: {
  attendedDates: string[];
  trackingSince: string | null;
}) {
  const attended = new Set(attendedDates);
  const ist = new Date(serverNow() + IST_OFFSET_MS);
  const year = ist.getUTCFullYear();
  const month = ist.getUTCMonth();
  const today = ist.getUTCDate();
  const lead = new Date(Date.UTC(year, month, 1)).getUTCDay();
  const days = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();

  const cells: (number | null)[] = [
    ...Array.from({ length: lead }, () => null),
    ...Array.from({ length: days }, (_, i) => i + 1),
  ];
  while (cells.length % 7) cells.push(null);
  const weeks = Array.from({ length: cells.length / 7 }, (_, w) => cells.slice(w * 7, w * 7 + 7));

  const pad = (n: number) => String(n).padStart(2, '0');
  const iso = (d: number) => `${year}-${pad(month + 1)}-${pad(d)}`;
  const stateOf = (d: number): DayState => {
    if (d === today) return 'TODAY';
    if (d > today) return 'FUTURE';
    if (attended.has(iso(d))) return 'ATTENDED';
    // Only days inside the membership can be missed. ISO dates compare as strings.
    if (trackingSince && iso(d) >= trackingSince) return 'MISSED';
    return 'NEUTRAL';
  };

  return (
    <View style={styles.calendar}>
      <View style={styles.rowBetween}>
        <Text style={styles.calMonth}>
          {MONTHS[month]} {year}
        </Text>
        <View style={styles.legend}>
          <View style={styles.legendItem}>
            <View style={[styles.legendDot, { backgroundColor: colors.plumBrand }]} />
            <Text style={styles.legendText}>Attended</Text>
          </View>
          <View style={styles.legendItem}>
            <View style={[styles.legendDot, { backgroundColor: MISSED_DOT }]} />
            <Text style={styles.legendText}>Missed</Text>
          </View>
        </View>
      </View>

      <View style={[styles.calWeek, styles.calWeekdays]}>
        {['S', 'M', 'T', 'W', 'T', 'F', 'S'].map((d, i) => (
          <Text key={`${d}-${i}`} style={styles.calWeekday}>
            {d}
          </Text>
        ))}
      </View>

      {weeks.map((week, w) => (
        <View key={w} style={styles.calWeek}>
          {week.map((d, i) => {
            if (d === null) return <View key={`b-${i}`} style={styles.calBlank} />;
            const state = stateOf(d);
            const look = CELL[state];
            return (
              <View
                key={d}
                style={[styles.calCell, { backgroundColor: look.bg }]}
                accessible
                accessibilityLabel={`${MONTHS[month]} ${d}${
                  state === 'ATTENDED' ? ', attended' : state === 'MISSED' ? ', missed' : state === 'TODAY' ? ', today' : ''
                }`}
              >
                <Text style={[styles.calDay, { color: look.fg, fontWeight: look.bold ? '700' : '500' }]}>
                  {d}
                </Text>
              </View>
            );
          })}
        </View>
      ))}
    </View>
  );
}

// ── Free user — a sales page with content in it (§7.2) ──────────────────────

/** Lambda 1444564674 — the design's five static rows, verbatim. */
const PROGRAMME = [
  ['Daily Asana & Flow', '365 days a year with senior instructors'],
  ['Pranayama & Yogic Breath', '52 deep breathing & vitality sessions'],
  ['Joint Health & Mobility', 'Injury-prevention for desk workers and runners'],
  ['Yogic Meditation Masterclasses', '12 monthly deep-dive intensives with Dr. Goswami'],
  ['Unified WhatsApp Class Link', 'Convenient reminders and 1-tap joining'],
] as const;

function FreeYoga({ expired }: { expired: boolean }) {
  const router = useRouter();
  const { data: catalog } = useYogaCatalog();
  const { data: today } = useYogaToday();
  const { data: workshops } = useWorkshops();
  const { data: content } = useContent();
  const free = catalog?.sessions.filter((s) => s.isFree) ?? [];
  const batches = today?.batches ?? [];
  const tracks = catalog?.categories.length ?? 0;

  const openPaywall = () => router.push({ pathname: '/paywall', params: { productId: 'yoga_annual' } });

  return (
    <View style={styles.root}>
      <ScrollView contentContainerStyle={styles.freeList} showsVerticalScrollIndicator={false}>
        {/* Hero — plain canvas content, padding(18, 8), with its own CTA. */}
        <View style={styles.sell}>
          <TagPill label={expired ? 'Previous member' : 'Yoga programme'} tone="CORAL" />
          <Text style={styles.sellTitle}>
            {expired ? 'Welcome back to your practice' : 'Eight live classes,\nevery single day'}
          </Text>
          <Text style={styles.sellBody}>
            A full morning and evening schedule taught by master instructors from The Yoga Institute.
            Join any batch on any day.
          </Text>
          <Pressable style={styles.sellBtn} onPress={openPaywall} accessibilityRole="button">
            <Text style={styles.sellBtnText}>
              {expired ? 'Renew Yoga Membership' : 'Subscribe — Explore Membership'}
            </Text>
          </Pressable>
        </View>

        <SectionHeader title="What the programme includes" style={styles.spaced10} />
        {PROGRAMME.map(([title, sub]) => (
          <View key={title} style={styles.include}>
            <Text style={styles.includeTitle}>{title}</Text>
            <Text style={styles.includeSub}>{sub}</Text>
          </View>
        ))}

        {/* §7.2 "what the programme is — the 5 tracks, the 8 daily batches".
            Read-only: a free user has nothing to join or be reminded of. The
            track count opens the catalogue, where every track can be browsed. */}
        {batches.length ? (
          <>
            <SectionHeader
              title={`${batches.length} live classes, every day`}
              action={tracks ? `${tracks} programme tracks` : undefined}
              onAction={() => router.push({ pathname: '/yoga-explorer' })}
              style={styles.spaced14}
            />
            <Text style={styles.note}>
              The full daily timetable. Members join any batch, any day, with one live link.
            </Text>
            <View style={styles.timetable}>
              {batches.map((b, i) => (
                <View key={b.id} style={[styles.ttRow, i > 0 && styles.ttRule]}>
                  <Text style={styles.ttTime}>{heroClock(b.startsAt)}</Text>
                  <View style={styles.flex}>
                    <Text style={styles.ttTitle} numberOfLines={1}>
                      {b.title}
                    </Text>
                    <Text style={styles.ttInstructor} numberOfLines={1}>
                      {b.instructorName}
                    </Text>
                  </View>
                </View>
              ))}
            </View>
          </>
        ) : null}

        {free.length ? (
          <>
            <SectionHeader title="Watch sample sessions (Free)" style={styles.spaced14} />
            <FlatList
              horizontal
              data={free}
              keyExtractor={(s) => s.id}
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.rail}
              renderItem={({ item }) => (
                <VideoCard
                  session={item}
                  locked={false}
                  onPress={() => router.push({ pathname: '/session/[id]', params: { id: item.id } })}
                />
              )}
            />
          </>
        ) : null}

        {/* §7.2 "free clips and instructor videos" — the same reels as Home,
            played in-app. Header copy is the design's Home reel rail. */}
        {content?.reels?.length ? (
          <>
            <SectionHeader title="Explore our instructors" style={styles.spaced14} />
            <FlatList
              horizontal
              data={content.reels}
              keyExtractor={(r) => r.id}
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.rail}
              renderItem={({ item }) => (
                <ReelCard
                  reel={item}
                  onPress={() =>
                    router.push({
                      pathname: '/reel',
                      params: { url: item.playbackUrl, title: item.instructorName, handle: item.instructorHandle },
                    })
                  }
                />
              )}
            />
          </>
        ) : null}

        {workshops?.workshops.length ? (
          <View style={styles.spaced16}>
            <WorkshopSection workshops={workshops.workshops} initialFilter="ALL" />
          </View>
        ) : null}

        {/* §7.2: instructors, The Yoga Institute, chief spiritual mentor — not
            in the design's free page, so they follow its sections. */}
        {content ? <InstructorsStrip instructors={content.instructors} /> : null}
        <InstituteCard />
        {content ? <MentorCard mentor={content.mentor} /> : null}
        {content ? <MemberQuotes quotes={content.quotes} /> : null}
      </ScrollView>

      {/* §7.2 "Subscribe CTA, persistent" — the design's closing plumDeep
          button, held on screen instead of sitting at the end of the page. */}
      <View style={styles.stickyBar}>
        <Pressable style={styles.stickyCta} onPress={openPaywall} accessibilityRole="button">
          <Text style={styles.stickyText}>Join Now — See Plans &amp; Pricing</Text>
        </Pressable>
      </View>
    </View>
  );
}

function Loading() {
  return (
    <View style={styles.center}>
      <ActivityIndicator color={colors.coralBrand} />
    </View>
  );
}

const WHITE_90 = 'rgba(255,255,255,0.9)';

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  flex: { flex: 1 },
  shrink: { flexShrink: 1 },
  rowBetween: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  gap8: { gap: 8 },
  spaced10: { marginTop: 10 },
  spaced14: { marginTop: 14 },
  spaced16: { marginTop: 16 },
  // contentPadding(bottom = 90) — clears the bottom nav with room to spare.
  list: { paddingBottom: 90 },
  rail: { paddingHorizontal: GUTTER, gap: 12 },
  // Lambda 836246698: Text(padding(18, 2)) straight under the SectionHeader.
  note: {
    paddingHorizontal: GUTTER,
    paddingVertical: 2,
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    color: colors.textMuted,
  },

  // Header + segment
  header: { paddingHorizontal: GUTTER, paddingVertical: 8 },
  headerTitle: { fontFamily: FONTS.serif, fontSize: 24, fontWeight: '700', color: colors.textPrimary },
  segment: {
    flexDirection: 'row',
    marginTop: 12,
    backgroundColor: colors.surfaceSand,
    borderRadius: radii.md,
    padding: 4,
  },
  segmentItem: { flex: 1, alignItems: 'center', paddingVertical: 8, borderRadius: 10 },
  segmentItemOn: { backgroundColor: colors.paperWhite },
  segmentText: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '500', color: colors.textSecondary },
  segmentTextOn: { fontWeight: '700', color: colors.plumDeep },

  // Next-session hero
  heroWrap: { paddingHorizontal: GUTTER, paddingVertical: 6 },
  hero: {
    borderRadius: radii.hero,
    borderWidth: 1,
    borderColor: colors.plumLine,
    overflow: 'hidden',
    backgroundColor: colors.plumDeep,
  },
  heroBody: { padding: 18 },
  heroTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  timeChip: {
    backgroundColor: 'rgba(0,0,0,0.45)',
    borderRadius: radii.tag,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  timeChipText: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.paperWhite },
  heroTitle: {
    marginTop: 10,
    fontFamily: FONTS.serif,
    fontSize: 22,
    lineHeight: 28,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  heroMeta: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 6 },
  heroMetaText: { flexShrink: 1, fontFamily: FONTS.sans, fontSize: 12, color: WHITE_90 },
  heroBtn: {
    marginTop: 14,
    height: 46,
    borderRadius: radii.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingHorizontal: 12,
  },
  heroBtnJoin: { backgroundColor: colors.coralBrand },
  heroBtnQuiet: { backgroundColor: 'rgba(255,255,255,0.16)' },
  heroBtnText: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.paperWhite },

  // Targeted anatomy card
  // Spacer(8), then Card(padding(18, 4)).
  anatomy: {
    marginTop: 12,
    marginBottom: 4,
    marginHorizontal: GUTTER,
    backgroundColor: colors.paperWhite,
    borderRadius: 18,
    borderWidth: 1,
    borderColor: colors.plumLine,
    padding: 14,
  },
  viewAll: { flexDirection: 'row', alignItems: 'center' },
  viewAllText: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.plumDeep },
  anatomyTitle: {
    marginTop: 8,
    fontFamily: FONTS.serif,
    fontSize: 16,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  anatomySub: {
    marginTop: 2,
    marginBottom: 10,
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    color: colors.textSecondary,
  },
  tile: {
    width: 135,
    height: 95,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    overflow: 'hidden',
  },
  tileBody: { flex: 1, padding: 8, justifyContent: 'space-between', alignItems: 'flex-start' },
  tileChip: { backgroundColor: 'rgba(0,0,0,0.5)', borderRadius: 4, paddingHorizontal: 5, paddingVertical: 2 },
  tileChipText: { fontFamily: FONTS.sans, fontSize: 7.5, fontWeight: '700', color: colors.paperWhite },
  tileName: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.paperWhite },

  // Batch rows
  batch: {
    marginHorizontal: GUTTER,
    marginVertical: 4,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
    backgroundColor: colors.paperWhite,
    borderRadius: radii.option,
    borderWidth: 1,
    borderColor: colors.borderRule,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  batchSlot: { backgroundColor: colors.plumTint, borderColor: colors.plumLine },
  eyebrowRow: { flexDirection: 'row', gap: 8 },
  slotEyebrow: {
    fontFamily: FONTS.sans,
    fontSize: 9,
    fontWeight: '800',
    letterSpacing: 0.5,
    color: colors.plumBrand,
  },
  batchTime: { fontFamily: FONTS.serif, fontSize: 16, fontWeight: '700', color: colors.textPrimary },
  batchMeta: { fontFamily: FONTS.sans, fontSize: 12, color: colors.textSecondary },
  batchBtn: {
    height: 36,
    borderRadius: radii.sm,
    paddingHorizontal: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceSand,
  },
  batchBtnSlot: { backgroundColor: colors.plumBrand },
  batchBtnText: { fontFamily: FONTS.sans, fontSize: 11.5, fontWeight: '700', color: colors.plumDeep },

  // Recording rows
  recording: {
    marginHorizontal: GUTTER,
    marginVertical: 4,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: colors.paperWhite,
    borderRadius: radii.option,
    borderWidth: 1,
    borderColor: colors.borderRule,
    padding: 10,
  },
  recordingLead: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 12 },
  recordingThumb: { width: 72, height: 52, borderRadius: 10, overflow: 'hidden' },
  recordingPlay: {
    position: 'absolute',
    top: 14,
    left: 24,
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: 'rgba(255,255,255,0.85)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  recordingDur: {
    position: 'absolute',
    right: 3,
    bottom: 3,
    backgroundColor: 'rgba(0,0,0,0.6)',
    borderRadius: 3,
    paddingHorizontal: 4,
    paddingVertical: 1,
  },
  recordingDurText: { fontFamily: FONTS.sans, fontSize: 8.5, fontWeight: '700', color: colors.paperWhite },
  recordingTitle: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.textPrimary },
  recordingMeta: { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 2 },
  recordingMetaText: { flexShrink: 1, fontFamily: FONTS.sans, fontSize: 11, color: colors.textMuted },
  watch: {
    paddingLeft: 6,
    fontFamily: FONTS.sans,
    fontSize: 12,
    fontWeight: '700',
    color: colors.plumBrand,
  },

  // Tracker
  tracker: { paddingHorizontal: GUTTER, paddingVertical: 8 },
  streak: { backgroundColor: colors.carbon950, borderRadius: radii.xl, padding: 20 },
  streakRow: { flexDirection: 'row', alignItems: 'flex-end', marginTop: 8 },
  streakNum: {
    fontFamily: MONO,
    fontSize: 54,
    lineHeight: 54,
    fontWeight: '700',
    color: colors.amberWarn,
  },
  streakUnit: {
    marginLeft: 8,
    paddingBottom: 8,
    fontFamily: FONTS.sans,
    fontSize: 16,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  streakNote: { fontFamily: FONTS.sans, fontSize: 12, color: 'rgba(255,255,255,0.7)' },
  miniRow: { flexDirection: 'row', gap: 8, marginTop: 12 },
  mini: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.paperWhite,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    padding: 10,
  },
  miniValue: { fontFamily: FONTS.serif, fontSize: 20, fontWeight: '700', color: colors.textPrimary },
  miniLabel: {
    fontFamily: FONTS.sans,
    fontSize: 9.5,
    lineHeight: 12,
    textAlign: 'center',
    color: colors.textMuted,
  },
  // M3 outlined Card: outlineVariant, which the design theme maps to borderSubtle.
  calendar: {
    marginTop: 16,
    backgroundColor: colors.paperWhite,
    borderRadius: 18,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    padding: 16,
  },
  calMonth: { fontFamily: FONTS.serif, fontSize: 17, fontWeight: '700', color: colors.textPrimary },
  legend: { flexDirection: 'row', gap: 8 },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  legendDot: { width: 8, height: 8, borderRadius: 4 },
  legendText: { fontFamily: FONTS.sans, fontSize: 10, color: colors.textMuted },
  // Spacer 14 and Spacer 8, less the 3dp row padding this row shares with the weeks.
  calWeekdays: { marginTop: 14 - 3, marginBottom: 8 - 3 },
  calWeekday: {
    flex: 1,
    textAlign: 'center',
    fontFamily: FONTS.sans,
    fontSize: 11,
    fontWeight: '700',
    color: colors.textMuted,
  },
  calWeek: { flexDirection: 'row', gap: 4, paddingVertical: 3 },
  calBlank: { flex: 1 },
  calCell: { flex: 1, aspectRatio: 1, borderRadius: radii.sm, alignItems: 'center', justifyContent: 'center' },
  calDay: { fontFamily: FONTS.sans, fontSize: 11 },
  sageNote: {
    marginTop: 14,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: colors.sageTint,
    borderRadius: radii.md,
    padding: 12,
  },
  leaf: { fontSize: 16 },
  sageText: { flex: 1, fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: 16, color: colors.sageBrand },

  // Free page
  freeList: { paddingBottom: 24 },
  sell: { paddingHorizontal: GUTTER, paddingVertical: 8 },
  sellTitle: {
    marginTop: 6,
    fontFamily: FONTS.serif,
    fontSize: 28,
    lineHeight: 32,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  sellBody: {
    marginTop: 6,
    marginBottom: 14,
    fontFamily: FONTS.sans,
    fontSize: 13,
    lineHeight: 18,
    color: colors.textSecondary,
  },
  sellBtn: {
    height: 50,
    borderRadius: radii.option,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sellBtnText: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '700', color: colors.paperWhite },
  include: {
    marginHorizontal: GUTTER,
    marginVertical: 4,
    backgroundColor: colors.paperWhite,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    padding: 12,
  },
  includeTitle: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.textPrimary },
  includeSub: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },
  timetable: {
    marginHorizontal: GUTTER,
    marginTop: 4,
    backgroundColor: colors.paperWhite,
    borderRadius: radii.option,
    borderWidth: 1,
    borderColor: colors.borderRule,
    overflow: 'hidden',
  },
  ttRow: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 14, paddingVertical: 10 },
  ttRule: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.borderRule },
  ttTime: { width: 84, fontFamily: FONTS.serif, fontSize: 16, fontWeight: '700', color: colors.textPrimary },
  ttTitle: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '600', color: colors.textPrimary },
  ttInstructor: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },
  stickyBar: {
    paddingHorizontal: GUTTER,
    paddingVertical: 10,
    backgroundColor: colors.canvasBg,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.borderRule,
  },
  stickyCta: {
    height: 50,
    borderRadius: radii.option,
    backgroundColor: colors.plumDeep,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stickyText: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '700', color: colors.paperWhite },
});
