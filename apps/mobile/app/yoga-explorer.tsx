import { useEffect, useRef, useState, type RefObject } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
  type ViewStyle,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { YogaCategory, YogaSession } from '@th/types';
import { useMySessions, useSession, useSetSaved, useYogaCatalog, useYogaToday } from '@/api/hooks';
import { CardImage } from '@/components/feed/Cards';
import { TagPill } from '@/components/TagPill';
import { InstructorAvatar, SalesMembershipBanner, groupThousands } from '@/components/YogaAbout';
import { FONTS, categoryBanner, categoryGradients, colors, layout, radii } from '@/theme';
import { ErrorState } from '@/components/QueryState';

/**
 * Yoga session explorer — the body-target catalogue (design
 * YogaSessionScreenKt.YogaCategoryExplorerView).
 *
 * Deeper than PRD §7.1 asks for, but it is what the design delivers and the
 * strongest content asset in it (docs/02 §2.2). The catalogue grows, so the
 * sessions are a virtualised FlatList — one full-width YogaSessionCard per
 * row, as the design draws them — with everything above them in the header.
 */

const GUTTER = layout.screenGutter;

/** Compose cardElevation(n) — Android elevation, an equivalent soft shadow on iOS. */
const cardElevation = (n: number): ViewStyle =>
  Platform.select<ViewStyle>({
    android: { elevation: n },
    default: {
      shadowColor: '#000',
      shadowOpacity: 0.08,
      shadowRadius: n * 2,
      shadowOffset: { width: 0, height: n / 2 },
    },
  });

/** YogaSessionCard's placeholder gradient per track (horizontal), before/without a photo. */
const SESSION_FALLBACK: Record<string, readonly [string, string]> = {
  cat_core: ['#5E3906', '#8C550A'],
  cat_flex: ['#6B2215', '#9E3622'],
  cat_morning: ['#0A472E', '#19734C'],
  cat_sleep: ['#0F3250', '#1A5282'],
  cat_spine: ['#381547', '#5A2272'],
};
const SESSION_FALLBACK_DEFAULT = ['#263D34', '#436959'] as const;

/** Center a rail on the active category (index 0 just returns to the start). */
function scrollRail(ref: RefObject<FlatList<YogaCategory> | null>, index: number) {
  if (index === 0) ref.current?.scrollToOffset({ offset: 0, animated: true });
  else ref.current?.scrollToIndex({ index, viewPosition: 0.5, animated: true });
}

export default function YogaExplorerScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ categoryId?: string }>();
  const { data, isLoading, isError, error, refetch } = useYogaCatalog();
  const { data: session } = useSession();
  const { data: mine } = useMySessions();
  const { data: today } = useYogaToday();
  const setSaved = useSetSaved();
  const [selected, setSelected] = useState<string | null>(params.categoryId ?? null);
  const cardsRef = useRef<FlatList<YogaCategory>>(null);
  const chipsRef = useRef<FlatList<YogaCategory>>(null);
  const firstScroll = useRef(true);

  // Opened on a category from a "See all" link: bring it into view in both
  // rails, or the user can't tell which track they're on (it may sit
  // off-screen right). Later picks keep the two rails in step.
  const activeIndex = data
    ? data.categories.findIndex((c) => c.id === (selected ?? data.categories[0]?.id))
    : -1;
  useEffect(() => {
    if (activeIndex < 0) return;
    const initial = firstScroll.current;
    firstScroll.current = false;
    if (initial && activeIndex === 0) return;
    const id = setTimeout(
      () => {
        scrollRail(cardsRef, activeIndex);
        scrollRail(chipsRef, activeIndex);
      },
      initial ? 250 : 0,
    );
    return () => clearTimeout(id);
  }, [activeIndex]);

  if (isError && !data) return <ErrorState error={error} onRetry={() => void refetch()} />;
  if (isLoading || !data) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.coralBrand} />
      </View>
    );
  }

  const entitled = session?.persona.hasYoga ?? false;
  const activeId = selected ?? data.categories[0]?.id ?? null;
  const category = data.categories.find((c) => c.id === activeId);
  const sessions = data.sessions.filter((s) => s.categoryId === activeId);
  const trained = data.categories.reduce((n, c) => n + c.totalYogisJoined, 0);

  // Chips and cards vary in measured position early on, so an early
  // scrollToIndex can miss; aim by average width, then settle.
  const onScrollFailed =
    (ref: RefObject<FlatList<YogaCategory> | null>) =>
    (info: { index: number; averageItemLength: number }) => {
      ref.current?.scrollToOffset({ offset: info.averageItemLength * info.index, animated: true });
      setTimeout(() => ref.current?.scrollToIndex({ index: info.index, viewPosition: 0.5 }), 300);
    };

  const openDetail = (s: YogaSession) => router.push({ pathname: '/session/[id]', params: { id: s.id } });

  const header = (
    <>
      <View style={styles.topBar}>
        <Pressable
          onPress={() => router.back()}
          style={styles.iconBtn}
          hitSlop={4}
          accessibilityRole="button"
          accessibilityLabel="Back to previous screen"
        >
          <MaterialIcons name="arrow-back" size={24} color={colors.textPrimary} />
        </Pressable>
        <View style={styles.topText}>
          <Text style={styles.title} accessibilityRole="header">
            Yoga Studio Catalog
          </Text>
          <Text style={styles.subtitle}>Targeted practices for anatomical health &amp; lifestyle</Text>
        </View>
      </View>

      <View style={styles.strip}>
        <View style={styles.stripLead}>
          <View style={styles.liveDot} />
          <Text style={styles.stripText}>{today?.batches.length || 8} Live Daily Batches</Text>
        </View>
        {trained > 0 ? (
          <Text style={styles.stripMeta}>
            {trained >= 1000 ? `${Math.floor(trained / 1000)}k+` : `${trained}+`} Yogis Trained
          </Text>
        ) : null}
      </View>

      <View style={styles.railHead}>
        <Text style={styles.railTitle}>Categories &amp; Anatomy Focus</Text>
        <Text style={styles.railCount}>{data.categories.length} Tracks</Text>
      </View>
      <FlatList
        ref={cardsRef}
        horizontal
        data={data.categories}
        keyExtractor={(c) => c.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.cardsRail}
        onScrollToIndexFailed={onScrollFailed(cardsRef)}
        renderItem={({ item }) => (
          <CategoryVisualCard category={item} selected={item.id === activeId} onPress={() => setSelected(item.id)} />
        )}
      />

      <FlatList
        ref={chipsRef}
        horizontal
        data={data.categories}
        keyExtractor={(c) => c.id}
        showsHorizontalScrollIndicator={false}
        style={styles.chipsList}
        contentContainerStyle={styles.chipsRail}
        onScrollToIndexFailed={onScrollFailed(chipsRef)}
        renderItem={({ item }) => {
          const on = item.id === activeId;
          return (
            <Pressable
              style={[styles.chip, on && styles.chipOn]}
              onPress={() => setSelected(item.id)}
              accessibilityRole="tab"
              accessibilityState={{ selected: on }}
            >
              <Text style={[styles.chipText, on && styles.chipTextOn]}>{item.name}</Text>
            </Pressable>
          );
        }}
      />

      {category ? <CategoryHeroBanner category={category} /> : null}

      {/* The pass pitch is for non-members only; a subscriber already has it. */}
      {!entitled ? (
        <View style={styles.passWrap}>
          <SalesMembershipBanner
            onPress={() => router.push({ pathname: '/paywall', params: { productId: 'yoga_annual' } })}
          />
        </View>
      ) : null}

      {category ? (
        <View style={styles.sessionsHead}>
          <Text style={styles.sessionsTitle}>Sessions in {category.name}</Text>
          <Text style={styles.sessionsCount}>
            {sessions.length} specialized guided session{sessions.length === 1 ? '' : 's'}
          </Text>
        </View>
      ) : null}
    </>
  );

  return (
    <SafeAreaView style={styles.root} edges={['top']}>
      <FlatList
        data={sessions}
        keyExtractor={(s) => s.id}
        ListHeaderComponent={header}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        renderItem={({ item }) => {
          const locked = !item.isFree && !entitled;
          const saved = mine?.savedSessionIds.includes(item.id) ?? false;
          return (
            <YogaSessionCard
              session={item}
              completed={mine?.completedSessionIds.includes(item.id) ?? false}
              saved={saved}
              onOpen={() => openDetail(item)}
              onToggleSave={() => setSaved.mutate({ id: item.id, on: !saved })}
              // §6.3: a locked session opens its preview (everything but
              // playback, plus the subscribe action) — never straight to a paywall.
              onStart={() =>
                locked ? openDetail(item) : router.push({ pathname: '/video/[id]', params: { id: item.id } })
              }
            />
          );
        }}
      />
    </SafeAreaView>
  );
}

/** YogaCategoryVisualCard — 152×115; the selected one gets the gold frame, check and chip. */
function CategoryVisualCard({
  category,
  selected,
  onPress,
}: {
  category: YogaCategory;
  selected: boolean;
  onPress: () => void;
}) {
  const target = category.bodyTargetSummary.split(',')[0]?.trim() ?? '';
  return (
    <Pressable
      onPress={onPress}
      style={[styles.visualOuter, cardElevation(selected ? 4 : 1)]}
      accessibilityRole="button"
      accessibilityState={{ selected }}
      accessibilityLabel={`${category.name}, ${category.sessionCount} sessions`}
    >
      <View
        style={[
          styles.visual,
          selected
            ? { borderWidth: 2.5, borderColor: colors.goldAccent }
            : { borderWidth: 1, borderColor: colors.borderRule },
        ]}
      >
        <CardImage uri={category.imageUrl} scrim={{ colors: ['rgba(0,0,0,0.25)', 'rgba(0,0,0,0.88)'] }} />
        <View style={styles.visualBody}>
          <View style={styles.rowBetween}>
            <View style={[styles.visualChip, { backgroundColor: selected ? colors.goldAccent : 'rgba(0,0,0,0.6)' }]}>
              <Text style={[styles.visualChipText, { color: selected ? colors.carbon950 : colors.paperWhite }]}>
                {category.sessionCount} {category.sessionCount === 1 ? 'SESSION' : 'SESSIONS'}
              </Text>
            </View>
            {selected ? (
              <View style={styles.check}>
                <MaterialIcons name="check" size={12} color={colors.carbon950} />
              </View>
            ) : null}
          </View>
          <View>
            <Text style={styles.visualName} numberOfLines={1}>
              {category.name}
            </Text>
            <Text style={styles.visualMeta} numberOfLines={1}>
              {Math.floor(category.totalYogisJoined / 1000)}k yogis · {target}
            </Text>
          </View>
        </View>
      </View>
    </Pressable>
  );
}

/** CategoryHeroBanner — 165dp, 18dp inset and radius; photo over the track gradient. */
function CategoryHeroBanner({ category }: { category: YogaCategory }) {
  return (
    <View style={styles.banner}>
      <CardImage
        uri={category.imageUrl}
        fallback={{
          // A theme added in the CMS before this build knows it: fall back, don't crash.
          colors: categoryGradients[category.bannerTheme as keyof typeof categoryGradients] ?? categoryGradients.PLUM,
          dir: 'horizontal',
        }}
        scrim={{ colors: ['rgba(0,0,0,0.35)', 'rgba(0,0,0,0.88)'] }}
      />
      <View style={styles.bannerBody}>
        <View style={styles.rowBetween}>
          <TagPill label="Targeted specialty" tone="GOLD" />
          <Text style={styles.bannerYogis}>{groupThousands(category.totalYogisJoined)}+ Yogis</Text>
        </View>
        <View>
          <Text style={styles.bannerName} numberOfLines={1}>
            {category.name}
          </Text>
          <Text style={styles.bannerTagline} numberOfLines={2}>
            {category.tagline}
          </Text>
          <View style={styles.bodyChip}>
            <MaterialIcons name="favorite" size={13} color={colors.coralBrand} />
            <Text style={styles.bodyChipText} numberOfLines={1}>
              Body Target: {category.bodyTargetSummary}
            </Text>
          </View>
        </View>
      </View>
    </View>
  );
}

/** YogaSessionCard — one per row: photo with status pills, then the practice summary and two actions. */
function YogaSessionCard({
  session,
  completed,
  saved,
  onOpen,
  onStart,
  onToggleSave,
}: {
  session: YogaSession;
  completed: boolean;
  saved: boolean;
  onOpen: () => void;
  onStart: () => void;
  onToggleSave: () => void;
}) {
  const fallback = SESSION_FALLBACK[session.categoryId] ?? SESSION_FALLBACK_DEFAULT;
  return (
    <Pressable style={[styles.sessionOuter, cardElevation(2)]} onPress={onOpen} accessibilityLabel={session.title}>
      <View style={styles.sessionCard}>
        <View style={styles.sessionImage}>
          <CardImage
            uri={session.imageUrl}
            fallback={{ colors: fallback, dir: 'horizontal' }}
            scrim={{ colors: ['rgba(0,0,0,0.28)', 'rgba(0,0,0,0.85)'] }}
          />
          <View style={styles.sessionImageBody}>
            <View style={styles.sessionTop}>
              <View style={styles.pills}>
                {session.isLive ? <TagPill label="Live now" tone="LIVE" /> : null}
                <TagPill label={`Est: ${session.durationMinutes} mins`} tone="NEUTRAL" />
                {session.isFree ? <TagPill label="Free pass" tone="EMERALD" /> : null}
                {completed ? <TagPill label="Completed ✓" tone="SAGE" /> : null}
              </View>
              <Pressable
                style={styles.saveBtn}
                onPress={onToggleSave}
                hitSlop={6}
                accessibilityRole="button"
                accessibilityLabel={saved ? 'Remove from saved' : 'Save session'}
              >
                <MaterialIcons
                  name={saved ? 'bookmark' : 'bookmark-border'}
                  size={18}
                  color={saved ? colors.goldAccent : colors.paperWhite}
                />
              </Pressable>
            </View>
            <View style={styles.rowBetween}>
              <View style={styles.joined}>
                <MaterialIcons name="groups" size={13} color="rgba(255,255,255,0.9)" />
                <Text style={styles.joinedText}>
                  {groupThousands(session.joinedCountTillDate)} Yogis Joined Till Date
                </Text>
              </View>
              <View style={styles.intensity}>
                <Text style={styles.intensityText}>{session.intensity}</Text>
              </View>
            </View>
          </View>
        </View>

        <View style={styles.sessionBody}>
          <Text style={styles.sessionTitle}>{session.title}</Text>
          <View style={styles.instructorRow}>
            <InstructorAvatar name={session.instructor.name} uri={session.instructor.avatarUrl} size={26} />
            <Text style={styles.instructorName} numberOfLines={1}>
              {session.instructor.name}
            </Text>
            <Text style={styles.rating}>· {session.instructor.rating.toFixed(1)} ★</Text>
          </View>
          <View style={styles.target}>
            <MaterialIcons name="fitness-center" size={14} color={colors.plumDeep} />
            <Text style={styles.targetText} numberOfLines={1}>
              Target: {session.bodyFocusTitle}
            </Text>
          </View>
          <View style={styles.actions}>
            <Pressable style={[styles.action, styles.actionOutline]} onPress={onOpen} accessibilityRole="button">
              <Text style={styles.actionOutlineText}>Details &amp; Benefits</Text>
            </Pressable>
            <Pressable
              style={[styles.action, { backgroundColor: session.isLive ? colors.liveEmerald : colors.coralBrand }]}
              onPress={onStart}
              accessibilityRole="button"
            >
              <MaterialIcons name="play-arrow" size={16} color={colors.paperWhite} />
              <Text style={styles.actionText}>{session.isLive ? 'Join Live' : 'Start Session'}</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.canvasBg },
  rowBetween: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  // PaddingValues(bottom = 96)
  list: { paddingBottom: 96 },

  // Header: padding(16, 12), IconButton, Spacer 4, title block.
  topBar: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 12 },
  iconBtn: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  topText: { flex: 1, marginLeft: 4 },
  title: { fontFamily: FONTS.serif, fontSize: 20, fontWeight: '700', color: colors.textPrimary },
  subtitle: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textSecondary },

  // "8 Live Daily Batches" strip: padding(18, 4), R10, plumTint, PlumDeep a0.15 border, padding 12/8.
  strip: {
    marginHorizontal: GUTTER,
    marginVertical: 4,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: colors.plumTint,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: 'rgba(59,14,74,0.15)',
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  stripLead: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  liveDot: { width: 7, height: 7, borderRadius: 3.5, backgroundColor: colors.liveEmerald },
  stripText: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '700', color: colors.plumDeep },
  stripMeta: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '500', color: colors.textSecondary },

  railHead: {
    marginTop: 10,
    paddingHorizontal: GUTTER,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  railTitle: { fontFamily: FONTS.serif, fontSize: 15, fontWeight: '700', color: colors.textPrimary },
  railCount: { fontFamily: FONTS.sans, fontSize: 11.5, fontWeight: '600', color: colors.coralBrand },
  // Spacer 8 above the rail; 4 of it is vertical padding so the selected card's shadow isn't cut.
  cardsRail: { paddingHorizontal: GUTTER, paddingVertical: 4, marginTop: 4, gap: 10 },
  visualOuter: { width: 152, height: 115, borderRadius: radii.lg, backgroundColor: colors.carbon900 },
  visual: { flex: 1, borderRadius: radii.lg, overflow: 'hidden' },
  visualBody: { flex: 1, padding: 10, justifyContent: 'space-between' },
  visualChip: { borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2 },
  visualChipText: { fontFamily: FONTS.sans, fontSize: 8.5, fontWeight: '900' },
  check: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: colors.goldAccent,
    alignItems: 'center',
    justifyContent: 'center',
  },
  visualName: { fontFamily: FONTS.serif, fontSize: 13.5, fontWeight: '700', color: colors.paperWhite },
  visualMeta: { fontFamily: FONTS.sans, fontSize: 9.5, color: 'rgba(255,255,255,0.85)' },

  // Spacer 12 (less the rail's 4 of shadow room) then the chip row.
  chipsList: { marginTop: 8 },
  chipsRail: { paddingHorizontal: GUTTER, gap: 8 },
  chip: {
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderRule,
    borderRadius: radii.xl,
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  chipOn: { backgroundColor: colors.plumDeep, borderColor: colors.plumDeep },
  chipText: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '500', color: colors.textPrimary },
  chipTextOn: { fontWeight: '700', color: colors.paperWhite },

  banner: {
    height: categoryBanner.height,
    marginHorizontal: categoryBanner.insetX,
    marginTop: 14,
    borderRadius: categoryBanner.radius,
    overflow: 'hidden',
    backgroundColor: colors.plumDeep,
  },
  bannerBody: { flex: 1, padding: 16, justifyContent: 'space-between' },
  bannerYogis: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '700', color: 'rgba(255,255,255,0.95)' },
  bannerName: { fontFamily: FONTS.serif, fontSize: 21, fontWeight: '700', color: colors.paperWhite },
  bannerTagline: {
    marginTop: 3,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 16,
    color: 'rgba(255,255,255,0.88)',
  },
  bodyChip: {
    marginTop: 8,
    alignSelf: 'flex-start',
    maxWidth: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: 'rgba(255,255,255,0.18)',
    borderRadius: radii.sm,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  bodyChipText: {
    flexShrink: 1,
    fontFamily: FONTS.sans,
    fontSize: 10.5,
    fontWeight: '600',
    color: colors.paperWhite,
  },

  passWrap: { marginTop: 10 },

  sessionsHead: { marginTop: 18, marginBottom: 8, paddingHorizontal: GUTTER },
  sessionsTitle: { fontFamily: FONTS.serif, fontSize: 17, fontWeight: '700', color: colors.textPrimary },
  sessionsCount: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },

  // YogaSessionCard: padding(18, 8), R18, PaperWhite, elevation 2, 1dp BorderRule.
  sessionOuter: {
    marginHorizontal: GUTTER,
    marginVertical: 8,
    borderRadius: 18,
    backgroundColor: colors.paperWhite,
  },
  sessionCard: {
    borderRadius: 18,
    borderWidth: 1,
    borderColor: colors.borderRule,
    overflow: 'hidden',
  },
  sessionImage: { height: 145, backgroundColor: colors.carbon900 },
  sessionImageBody: { flex: 1, padding: 12, justifyContent: 'space-between' },
  sessionTop: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 },
  pills: { flex: 1, flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  saveBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: 'rgba(0,0,0,0.5)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  joined: { flexDirection: 'row', alignItems: 'center', gap: 4, flexShrink: 1 },
  joinedText: { fontFamily: FONTS.sans, fontSize: 10.5, fontWeight: '600', color: 'rgba(255,255,255,0.95)' },
  intensity: { backgroundColor: 'rgba(0,0,0,0.45)', borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2 },
  intensityText: { fontFamily: FONTS.sans, fontSize: 10, fontWeight: '700', color: colors.paperWhite },
  sessionBody: { padding: 14 },
  sessionTitle: {
    fontFamily: FONTS.serif,
    fontSize: 16.5,
    lineHeight: 22,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  instructorRow: { flexDirection: 'row', alignItems: 'center', marginTop: 8 },
  instructorName: {
    marginLeft: 8,
    flexShrink: 1,
    fontFamily: FONTS.sans,
    fontSize: 12.5,
    fontWeight: '500',
    color: colors.textPrimary,
  },
  rating: {
    marginLeft: 6,
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    fontWeight: '600',
    color: colors.goldAccent,
  },
  target: {
    marginTop: 10,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.surfaceSand,
    borderRadius: radii.sm,
    paddingHorizontal: 10,
    paddingVertical: 7,
  },
  targetText: { flexShrink: 1, fontFamily: FONTS.sans, fontSize: 11, fontWeight: '600', color: colors.plumDeep },
  actions: { flexDirection: 'row', gap: 8, marginTop: 12 },
  action: {
    flex: 1,
    height: 44,
    borderRadius: 10,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
  },
  actionOutline: { borderWidth: 1, borderColor: colors.borderRule },
  actionOutlineText: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '600', color: colors.textPrimary },
  actionText: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.paperWhite },
});
