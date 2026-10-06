import { ActivityIndicator, Platform, Pressable, ScrollView, StyleSheet, Text, View, type ViewStyle } from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useMySessions, useSession, useSetCompleted, useSetSaved, useYogaCatalog } from '@/api/hooks';
import { CardImage } from '@/components/feed/Cards';
import { TagPill } from '@/components/TagPill';
import { InstructorAvatar, SalesMembershipBanner, groupThousands } from '@/components/YogaAbout';
import { FONTS, colors, layout, radii } from '@/theme';
import { ErrorState } from '@/components/QueryState';

/**
 * Session detail — design YogaSessionScreenKt.YogaSessionDetailView: the
 * therapeutic depth of each practice (body targets, lifestyle impact, the
 * asana sequence) in the design's order — top bar, hero, actions, lifestyle
 * card, instructor, sequence, pass banner.
 *
 * A locked session still shows everything EXCEPT playback (§6.3: a free user
 * tapping a paid item gets preview + subscription, never a dead tap).
 */

const GUTTER = layout.screenGutter;

/** Compose cardElevation(1) on a borderless PaperWhite card. */
const CARD_SHADOW: ViewStyle = Platform.select<ViewStyle>({
  android: { elevation: 1 },
  default: { shadowColor: '#000', shadowOpacity: 0.06, shadowRadius: 3, shadowOffset: { width: 0, height: 1 } },
});

/** The hero's placeholder gradient per track (vertical), shown before/without the photo. */
const HERO_FALLBACK: Record<string, readonly [string, string]> = {
  cat_core: ['#5E3906', '#2E1B02'],
  cat_flex: ['#6B2215', '#330E07'],
  cat_morning: ['#0A472E', '#042417'],
  cat_sleep: ['#0F3250', '#071929'],
  cat_spine: ['#381547', '#1B0B24'],
};
const HERO_FALLBACK_DEFAULT = ['#263D34', '#111E19'] as const;

export default function SessionDetailScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const { data: catalog, isLoading, isError, error, refetch } = useYogaCatalog();
  const { data: session } = useSession();
  const { data: mine } = useMySessions();
  const setSaved = useSetSaved();
  const setCompleted = useSetCompleted();

  const item = catalog?.sessions.find((s) => s.id === id);

  if (isError && !catalog) {
    return <ErrorState error={error} onRetry={() => void refetch()} onBack={() => router.back()} />;
  }
  // A stale or bad link: the catalogue loaded but has no such session.
  if (catalog && !item) {
    return <ErrorState message="This session isn’t available any more." onBack={() => router.back()} />;
  }
  if (isLoading || !item) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.coralBrand} />
      </View>
    );
  }

  const entitled = session?.persona.hasYoga ?? false;
  const locked = !item.isFree && !entitled;
  const saved = mine?.savedSessionIds.includes(item.id) ?? false;
  const completed = mine?.completedSessionIds.includes(item.id) ?? false;
  const openPaywall = () => router.push({ pathname: '/paywall', params: { productId: 'yoga_annual' } });

  return (
    <SafeAreaView style={styles.root} edges={['top']}>
      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        {/* Top bar on the canvas: padding(14, 8), ArrowBack + bookmark. */}
        <View style={styles.topBar}>
          <Pressable
            onPress={() => router.back()}
            style={styles.iconBtn}
            hitSlop={4}
            accessibilityRole="button"
            accessibilityLabel="Back to sessions list"
          >
            <MaterialIcons name="arrow-back" size={24} color={colors.textPrimary} />
          </Pressable>
          <Pressable
            onPress={() => setSaved.mutate({ id: item.id, on: !saved })}
            style={styles.iconBtn}
            hitSlop={4}
            accessibilityRole="button"
            accessibilityLabel={saved ? 'Remove bookmark' : 'Bookmark session'}
          >
            <MaterialIcons
              name={saved ? 'bookmark' : 'bookmark-border'}
              size={24}
              color={saved ? colors.goldAccent : colors.textPrimary}
            />
          </Pressable>
        </View>

        {/* Inset hero: padding(h = 18), R20, height wraps its content. */}
        <View style={styles.hero}>
          <CardImage
            uri={item.imageUrl}
            fallback={{ colors: HERO_FALLBACK[item.categoryId] ?? HERO_FALLBACK_DEFAULT, dir: 'vertical' }}
            scrim={{ colors: ['rgba(0,0,0,0.45)', 'rgba(0,0,0,0.88)'] }}
          />
          <View style={styles.heroBody}>
            <View style={styles.heroPills}>
              {item.isLive ? (
                <TagPill label="Live now" tone="LIVE" />
              ) : (
                <TagPill label="On-demand class" tone="NEUTRAL" />
              )}
              <TagPill label={`${item.durationMinutes} mins · ${item.caloriesBurned} kcal`} tone="GOLD" />
            </View>
            <Text style={styles.heroTitle}>{item.title}</Text>
            <Text style={styles.heroDesc}>{item.description}</Text>
            <View style={styles.joinedRow}>
              <View style={styles.joinedLead}>
                <MaterialIcons name="groups" size={18} color={colors.coralBrand} />
                <Text style={styles.joinedText} numberOfLines={1}>
                  {groupThousands(item.joinedCountTillDate)} Yogis Joined Till Date
                </Text>
              </View>
              <Text style={styles.todayText}>{item.todayActiveCount} today</Text>
            </View>
          </View>
        </View>

        {/* Main action, then Save / Mark Complete. */}
        <View style={styles.actions}>
          <Pressable
            style={[styles.mainBtn, { backgroundColor: item.isLive && !locked ? colors.liveEmerald : colors.coralBrand }]}
            onPress={() =>
              locked ? openPaywall() : router.push({ pathname: '/video/[id]', params: { id: item.id } })
            }
            accessibilityRole="button"
          >
            {/* A locked session is a preview (§6.3): its one action is to
                subscribe — offered in the brand colour, never greyed (§4). */}
            {locked ? null : <MaterialIcons name="play-arrow" size={20} color={colors.paperWhite} />}
            <Text style={styles.mainBtnText}>
              {locked ? 'Subscribe to Watch' : item.isLive ? 'Join Live Class Now' : 'Start Practice Session'}
            </Text>
          </Pressable>

          <View style={styles.secondaryRow}>
            <Pressable
              style={[styles.secondaryBtn, styles.saveBtn, saved && { borderColor: colors.goldAccent }]}
              onPress={() => setSaved.mutate({ id: item.id, on: !saved })}
              accessibilityRole="button"
            >
              <MaterialIcons
                name={saved ? 'bookmark' : 'bookmark-border'}
                size={16}
                color={saved ? colors.goldAccent : colors.textPrimary}
              />
              <Text style={[styles.secondaryText, { color: saved ? colors.goldAccent : colors.textPrimary }]}>
                {saved ? 'Saved' : 'Save Session'}
              </Text>
            </Pressable>
            {/* "Complete" is its own list — it never touches the attendance
                ledger, which only live classes write (§7.1). */}
            <Pressable
              style={[styles.secondaryBtn, { backgroundColor: completed ? colors.sageBrand : colors.surfaceSand }]}
              onPress={() => setCompleted.mutate({ id: item.id, on: !completed })}
              accessibilityRole="button"
            >
              <MaterialIcons
                name={completed ? 'check-circle' : 'check-circle-outline'}
                size={16}
                color={completed ? colors.paperWhite : colors.textPrimary}
              />
              <Text style={[styles.secondaryText, { color: completed ? colors.paperWhite : colors.textPrimary }]}>
                {completed ? 'Completed ✓' : 'Mark Complete'}
              </Text>
            </Pressable>
          </View>
        </View>

        {/* Body Targets & Lifestyle Impact — one card (+18). */}
        <View style={[styles.card, styles.cardFirst]}>
          <View style={styles.cardHead}>
            <View style={styles.cardHeadIcon}>
              <MaterialIcons name="accessibility-new" size={16} color={colors.plumDeep} />
            </View>
            <View style={styles.flex}>
              <Text style={styles.cardTitle}>Body Targets &amp; Lifestyle Impact</Text>
              <Text style={styles.cardSub}>Where this practice works in your body &amp; daily life</Text>
            </View>
          </View>

          <Text style={[styles.label, styles.labelFirst]}>ANATOMICAL FOCUS AREAS</Text>
          <View style={styles.chips}>
            {item.targetBodyParts.map((p) => (
              <View key={p} style={styles.chip}>
                <Text style={styles.chipText}>{p}</Text>
              </View>
            ))}
          </View>

          <Text style={[styles.label, styles.labelNext]}>HOW THIS IMPROVES YOUR LIFESTYLE</Text>
          <View style={styles.impact}>
            <MaterialIcons name="check" size={18} color={colors.sageBrand} style={styles.impactIcon} />
            <Text style={styles.impactText}>{item.lifestyleImpact}</Text>
          </View>
        </View>

        {/* Your instructor (+16). */}
        <View style={styles.card}>
          <Text style={styles.label}>YOUR INSTRUCTOR</Text>
          <View style={styles.instructorRow}>
            <InstructorAvatar name={item.instructor.name} uri={item.instructor.avatarUrl} size={52} />
            <View style={styles.flex}>
              <View style={styles.nameRow}>
                <Text style={styles.instructorName} numberOfLines={1}>
                  {item.instructor.name}
                </Text>
                <MaterialIcons
                  name="verified"
                  size={15}
                  color={colors.goldAccent}
                  accessibilityLabel="Verified Master Instructor"
                />
              </View>
              <Text style={styles.instructorTitle}>{item.instructor.title}</Text>
              <Text style={styles.instructorExp}>{item.instructor.experience}</Text>
            </View>
          </View>
          {item.instructor.bio ? <Text style={styles.bio}>{item.instructor.bio}</Text> : null}
        </View>

        {/* Practice sequence & asanas (+16). */}
        {item.keyPoses.length ? (
          <View style={styles.card}>
            <Text style={styles.label}>PRACTICE SEQUENCE &amp; ASANAS</Text>
            <View style={styles.poses}>
              {item.keyPoses.map((p, i) => (
                <View key={`${i}-${p}`} style={styles.pose}>
                  <View style={styles.poseNum}>
                    <Text style={styles.poseNumText}>{i + 1}</Text>
                  </View>
                  <Text style={styles.poseText}>{p}</Text>
                </View>
              ))}
            </View>
          </View>
        ) : null}

        {/* The pass pitch (+16) is for non-members; a subscriber already has it. */}
        {!entitled ? (
          <View style={styles.passWrap}>
            <SalesMembershipBanner onPress={openPaywall} />
          </View>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.canvasBg },
  flex: { flex: 1 },
  // PaddingValues(bottom = 96)
  scroll: { paddingBottom: 96 },

  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  iconBtn: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },

  hero: { marginHorizontal: GUTTER, borderRadius: radii.xl, overflow: 'hidden', backgroundColor: colors.carbon900 },
  heroBody: { padding: 20 },
  heroPills: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  heroTitle: {
    marginTop: 16,
    fontFamily: FONTS.serif,
    fontSize: 22,
    lineHeight: 28,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  heroDesc: {
    marginTop: 10,
    fontFamily: FONTS.sans,
    fontSize: 13,
    lineHeight: 18,
    color: 'rgba(255,255,255,0.85)',
  },
  joinedRow: {
    marginTop: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    backgroundColor: 'rgba(255,255,255,0.12)',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 9,
  },
  joinedLead: { flexDirection: 'row', alignItems: 'center', gap: 8, flexShrink: 1 },
  joinedText: { flexShrink: 1, fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.paperWhite },
  todayText: { fontFamily: FONTS.sans, fontSize: 11.5, fontWeight: '500', color: colors.liveEmerald },

  actions: { marginTop: 16, paddingHorizontal: GUTTER },
  mainBtn: {
    height: 52,
    borderRadius: radii.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  mainBtnText: { fontFamily: FONTS.sans, fontSize: 15, fontWeight: '700', color: colors.paperWhite },
  secondaryRow: { flexDirection: 'row', gap: 10, marginTop: 10 },
  secondaryBtn: {
    flex: 1,
    height: 46,
    borderRadius: 10,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
  },
  saveBtn: { borderWidth: 1, borderColor: colors.borderRule },
  secondaryText: { fontFamily: FONTS.sans, fontSize: 12.5, fontWeight: '600' },

  // Card(padding h18, RCS 16, PaperWhite, elevation 1): no border, inner padding 16.
  card: {
    marginTop: 16,
    marginHorizontal: GUTTER,
    backgroundColor: colors.paperWhite,
    borderRadius: radii.lg,
    padding: 16,
    ...CARD_SHADOW,
  },
  cardFirst: { marginTop: 18 },
  cardHead: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  cardHeadIcon: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: colors.plumTint,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cardTitle: { fontFamily: FONTS.serif, fontSize: 16, fontWeight: '700', color: colors.textPrimary },
  cardSub: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },
  label: {
    fontFamily: FONTS.sans,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 0.5,
    color: colors.textSecondary,
  },
  labelFirst: { marginTop: 12 },
  labelNext: { marginTop: 14 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 6 },
  chip: { backgroundColor: colors.plumTint, borderRadius: radii.sm, paddingHorizontal: 10, paddingVertical: 5 },
  chipText: { fontFamily: FONTS.sans, fontSize: 11.5, fontWeight: '600', color: colors.plumDeep },
  impact: {
    marginTop: 6,
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    backgroundColor: colors.surfaceSand,
    borderRadius: 10,
    padding: 12,
  },
  impactIcon: { marginTop: 2 },
  impactText: { flex: 1, fontFamily: FONTS.sans, fontSize: 12.5, lineHeight: 18, color: colors.textPrimary },

  instructorRow: { flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 10 },
  nameRow: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  instructorName: { flexShrink: 1, fontFamily: FONTS.sans, fontSize: 16, fontWeight: '700', color: colors.textPrimary },
  instructorTitle: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textSecondary },
  instructorExp: { fontFamily: FONTS.sans, fontSize: 11, color: colors.textMuted },
  bio: { marginTop: 12, fontFamily: FONTS.sans, fontSize: 12, lineHeight: 17, color: colors.textSecondary },

  poses: { marginTop: 10 },
  pose: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 4 },
  poseNum: {
    width: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: colors.plumTint,
    alignItems: 'center',
    justifyContent: 'center',
  },
  poseNumText: { fontFamily: FONTS.sans, fontSize: 10.5, fontWeight: '700', color: colors.plumDeep },
  poseText: { flex: 1, fontFamily: FONTS.sans, fontSize: 12.5, fontWeight: '500', color: colors.textPrimary },

  passWrap: { marginTop: 16 },
});
