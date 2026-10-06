import { useCallback, useEffect } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useRouter } from 'expo-router';
import type { FeedAction, HeroSlot, Reel } from '@th/types';
import { useHomeFeed, useSession } from '@/api/hooks';
import { useJoinClass } from '@/lib/joinClass';
import { openExternal } from '@/lib/links';
import { FeedComponentView } from '@/components/feed/FeedRenderer';
import { NotificationPrimer } from '@/components/NotificationPrimer';
import { syncServerTime } from '@/lib/time';
import { FONTS, colors, layout, spacing, type } from '@/theme';

/**
 * HOME — PRD §6.
 *
 * Three zones top to bottom: a state-driven hero banner, a content feed of
 * rails, and a promo strip sitting inside the feed. All three are assembled by
 * the server; this screen renders the list and routes the actions.
 *
 * The brand bar, bell and profile avatar are the shared TopHeader that the
 * tabs layout mounts above every tab (MainActivityKt's Scaffold topBar), so
 * this screen starts directly with the greeting — which, as in the design,
 * scrolls away with the feed rather than staying pinned.
 */
export default function HomeScreen() {
  const router = useRouter();
  const { data: session } = useSession();
  const { data, isLoading, refetch, isRefetching } = useHomeFeed();
  const { joinClass, joiningBatchId } = useJoinClass();

  useEffect(() => {
    if (data?.serverTime) syncServerTime(data.serverTime);
  }, [data?.serverTime]);

  const entitledToYoga = session?.persona.hasYoga ?? false;
  // PromoStrip (HomeScreenKt): gold for members holding both products.
  const holdsBoth = (session?.persona.hasYoga && session?.persona.hasMarathon) ?? false;

  const handleAction = useCallback(
    (action: FeedAction) => {
      switch (action.type) {
        case 'OPEN_RUN_TRACKER':
          router.push('/run-tracker');
          break;
        case 'OPEN_YOGA_EXPLORER':
          router.push({
            pathname: '/yoga-explorer',
            params: action.categoryId ? { categoryId: action.categoryId } : {},
          });
          break;
        case 'OPEN_YOGA_SESSION':
          router.push({ pathname: '/session/[id]', params: { id: action.sessionId } });
          break;
        case 'OPEN_RACE_DETAIL':
          router.push({ pathname: '/race/[eventId]', params: { eventId: action.eventId } });
          break;
        case 'OPEN_RACE_RESULTS':
          router.push({ pathname: '/race/[eventId]/results', params: { eventId: action.eventId } });
          break;
        case 'OPEN_DIGITAL_BIB':
          router.push({ pathname: '/bib/[eventId]', params: { eventId: action.eventId } });
          break;
        case 'OPEN_PAYWALL':
          router.push({ pathname: '/paywall', params: { productId: action.productId } });
          break;
        case 'OPEN_DIET_LEAD_FORM':
          router.push('/(tabs)/diet');
          break;
        case 'OPEN_TAB':
          // Home is the tabs index route; the others are named.
          router.push((action.tab === 'HOME' ? '/(tabs)' : `/(tabs)/${action.tab.toLowerCase()}`) as never);
          break;
        case 'OPEN_WORKSHOP':
          router.push('/(tabs)/yoga');
          break;
        case 'JOIN_LIVE_SESSION':
          // Joining only counts inside the class window (§7.1); outside it the
          // server refuses and useJoinClass explains — never a dead tap.
          if (!joiningBatchId) joinClass(action.batchId);
          break;
        case 'OPEN_ARTICLE':
        case 'OPEN_EXTERNAL':
          void openExternal(action.url);
          break;
        default:
          // An action from a newer server this build doesn't know: Home is
          // the safe landing rather than a tap that does nothing.
          router.push('/(tabs)');
      }
    },
    [router, joinClass, joiningBatchId],
  );

  const handleHeroPress = useCallback(
    (slot: HeroSlot) => {
      switch (slot.kind) {
        case 'YOGA_SESSION':
          // Only a live class or an open wait room is joinable — joining
          // writes the attendance mark server-side (§7.1). A session hours
          // away ("View Schedule") opens the Yoga tab instead; tapping it must
          // never count as attending.
          if (slot.state === 'LIVE' || slot.state === 'STARTING_SOON') {
            if (!joiningBatchId) joinClass(slot.batchId);
          }
          else handleAction({ type: 'OPEN_TAB', tab: 'YOGA' });
          break;
        case 'YOGA_RENEW':
          handleAction({ type: 'OPEN_PAYWALL', productId: 'yoga_annual' });
          break;
        case 'MY_RACE':
          // Race morning the CTA reads "Open Digital Bib & Pass": the pass is
          // what the runner needs at the gate, so it opens directly.
          handleAction(
            slot.isRaceDay
              ? { type: 'OPEN_DIGITAL_BIB', eventId: slot.eventId }
              : { type: 'OPEN_RACE_DETAIL', eventId: slot.eventId },
          );
          break;
        case 'RACE_RESULT':
          handleAction({ type: 'OPEN_RACE_RESULTS', eventId: slot.eventId });
          break;
        case 'SELL_YOGA':
          handleAction({ type: 'OPEN_PAYWALL', productId: slot.planId });
          break;
        case 'SELL_MARATHON':
          handleAction({ type: 'OPEN_RACE_DETAIL', eventId: slot.eventId });
          break;
      }
    },
    [handleAction, joinClass, joiningBatchId],
  );

  const handleSessionPress = useCallback(
    // Never a dead tap (§6.3): a locked item opens its PREVIEW — the session
    // page shows everything but playback, with the subscribe action — rather
    // than jumping straight to a paywall.
    (sessionId: string) => handleAction({ type: 'OPEN_YOGA_SESSION', sessionId }),
    [handleAction],
  );

  const handleReelPress = useCallback(
    // §6.3 rail 4: instructor reels play in-app, full screen.
    (reel: Reel) =>
      router.push({
        pathname: '/reel',
        params: { url: reel.playbackUrl, title: reel.instructorName, handle: reel.instructorHandle },
      }),
    [router],
  );

  if (isLoading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.coralBrand} />
      </View>
    );
  }

  // A failed refresh keeps the feed already on screen; only a first load that
  // failed has nothing to show.
  if (!data) {
    return (
      <View style={styles.center}>
        <Text style={type.headlineSmall}>Couldn&rsquo;t load your feed</Text>
        <Text style={[type.bodyMedium, { color: colors.textMuted, marginTop: 4 }]}>
          Check your connection and pull to retry.
        </Text>
        <Pressable style={styles.retry} onPress={() => void refetch()}>
          <Text style={[type.titleSmall, { color: colors.paperWhite }]}>Try again</Text>
        </Pressable>
      </View>
    );
  }

  // The design greets by first name only, and never by an empty string.
  const firstName = data.userName?.trim().split(/\s+/)[0] || 'there';

  return (
    <View style={styles.root}>
      <FlatList
        data={data.components}
        keyExtractor={(c) => c.id}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingBottom: spacing['9xl'] }}
        refreshControl={
          <RefreshControl
            refreshing={isRefetching}
            onRefresh={() => void refetch()}
            tintColor={colors.coralBrand}
            colors={[colors.coralBrand]}
          />
        }
        ListHeaderComponent={
          <View style={styles.greeting}>
            <Text style={styles.greetingLine} numberOfLines={1}>
              {data.greeting}
            </Text>
            <Text style={styles.hello} numberOfLines={1}>
              {`Hello, ${firstName} \u{1F44B}`}
            </Text>
          </View>
        }
        renderItem={({ item }) => (
          <FeedComponentView
            component={item}
            entitledToYoga={entitledToYoga}
            holdsBoth={holdsBoth}
            onAction={handleAction}
            onHeroPress={handleHeroPress}
            onSessionPress={handleSessionPress}
            onReelPress={handleReelPress}
          />
        )}
      />

      {/* §11: asked once, after onboarding — and for returning subscribers, who
          skip onboarding, on their first Home visit. */}
      <NotificationPrimer />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.canvasBg,
    padding: spacing['6xl'],
  },
  // HomeScreenKt greeting item: 18 × 8, muted day line over a serif hello.
  greeting: { paddingHorizontal: layout.screenGutter, paddingVertical: 8 },
  greetingLine: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    fontWeight: '500',
    color: colors.textMuted,
  },
  hello: {
    fontFamily: FONTS.serif,
    fontSize: 24,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  retry: {
    marginTop: spacing['5xl'],
    backgroundColor: colors.coralBrand,
    borderRadius: 12,
    paddingHorizontal: spacing['6xl'],
    paddingVertical: spacing.xl,
  },
});
