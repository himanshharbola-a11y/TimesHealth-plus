import { FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import type { FeedAction, FeedComponent, HeroSlot, Reel } from '@th/types';
import { FONTS, colors, layout } from '@/theme';
import { HeroCard, isKnownHeroSlot } from './Hero';
import { HeroSecondaryCard } from './HeroSecondaryCard';
import { ArticleCard, EntryTile, PromoStrip, QuoteCard, ReelCard, VideoCard } from './Cards';
import { WorkshopSection } from '../WorkshopSection';

/**
 * Renders the server's component list in order.
 *
 * The important rule lives in the `default` branch: an unknown component type
 * is SKIPPED, never an error. That is what lets the server ship a new rail to
 * users who have not updated, and it is why §13's cut list can be a config
 * change rather than a release. Hero slots get the same treatment.
 *
 * Spacing is the design's LazyColumn rhythm (HomeScreenKt): every block owns
 * its own padding and there are no extra margins between blocks —
 *   hero slots        18 × 6
 *   section header    18 × 12   (CommonComponentsKt.SectionHeader)
 *   rails             18 side padding, 12 between cards
 *   promo / run tile  18 × 14
 *   workshops         10 above
 */

const GUTTER = layout.screenGutter;

interface Props {
  component: FeedComponent;
  entitledToYoga: boolean;
  /** Holds both a yoga membership and a race — the design's gold promo variant. */
  holdsBoth: boolean;
  onAction: (action: FeedAction) => void;
  onHeroPress: (slot: HeroSlot) => void;
  onSessionPress: (sessionId: string, locked: boolean) => void;
  onReelPress: (reel: Reel) => void;
}

export function FeedComponentView({
  component,
  entitledToYoga,
  holdsBoth,
  onAction,
  onHeroPress,
  onSessionPress,
  onReelPress,
}: Props) {
  switch (component.type) {
    case 'HERO_STACK': {
      const [first, second] = (component.slots ?? []).filter(isKnownHeroSlot);
      if (!first) return null;
      return (
        <View>
          <View style={styles.heroItem}>
            <HeroCard slot={first} onPress={onHeroPress} />
          </View>
          {second ? (
            <View style={styles.heroItem}>
              <HeroSecondaryCard slot={second} onPress={onHeroPress} />
            </View>
          ) : null}
        </View>
      );
    }

    case 'VIDEO_RAIL': {
      // Older servers sent no actionLabel; they only ever offered "See all".
      const label =
        component.actionLabel !== undefined
          ? component.actionLabel
          : component.seeAllCategoryId
            ? 'See all'
            : null;
      return (
        <View>
          <SectionHeader
            title={component.title}
            actionLabel={label}
            // "See all" opens the concern's category; "Explore" (free rail,
            // no category) opens the whole catalogue.
            onAction={() =>
              onAction({ type: 'OPEN_YOGA_EXPLORER', categoryId: component.seeAllCategoryId ?? null })
            }
          />
          <FlatList
            horizontal
            data={component.items ?? []}
            keyExtractor={(s) => s.id}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.railContent}
            renderItem={({ item }) => {
              // §6.3: a free user tapping a paid item gets preview + paywall,
              // never a dead tap. `locked` drives the affordance, the handler
              // decides where it goes.
              const locked = !item.isFree && !entitledToYoga;
              return (
                <VideoCard
                  session={item}
                  locked={locked}
                  onPress={() => onSessionPress(item.id, locked)}
                />
              );
            }}
          />
        </View>
      );
    }

    case 'REEL_RAIL':
      return (
        <View>
          <SectionHeader title={component.title} />
          <FlatList
            horizontal
            data={component.items ?? []}
            keyExtractor={(r) => r.id}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.railContent}
            // §6.3 "plays inline": reels open the in-app player, not a browser.
            renderItem={({ item }) => <ReelCard reel={item} onPress={() => onReelPress(item)} />}
          />
        </View>
      );

    case 'ARTICLE_RAIL':
      return (
        <View>
          <SectionHeader title={component.title} />
          <FlatList
            horizontal
            data={component.items ?? []}
            keyExtractor={(a) => a.id}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.railContent}
            renderItem={({ item }) => (
              <ArticleCard
                article={item}
                onPress={() => onAction({ type: 'OPEN_ARTICLE', url: item.url })}
              />
            )}
          />
        </View>
      );

    case 'QUOTE_RAIL':
      return (
        <View>
          <SectionHeader title={component.title} />
          <FlatList
            horizontal
            data={component.items ?? []}
            keyExtractor={(q) => q.id}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.railContent}
            renderItem={({ item }) => <QuoteCard quote={item} />}
          />
        </View>
      );

    case 'ENTRY_TILE':
      return (
        <View style={styles.tileItem}>
          <EntryTile
            title={component.title}
            subtitle={component.subtitle}
            imageUrl={component.imageUrl}
            onPress={() => onAction(component.action)}
          />
        </View>
      );

    case 'PROMO_STRIP':
      return (
        <View style={styles.tileItem}>
          <PromoStrip
            title={component.title}
            subtitle={component.subtitle}
            ctaLabel={component.ctaLabel}
            backgroundColor={component.backgroundColor}
            imageUrl={component.imageUrl}
            gold={holdsBoth}
            onPress={() => onAction(component.action)}
          />
        </View>
      );

    case 'WORKSHOP_RAIL':
      return (
        <View style={styles.workshops}>
          <WorkshopSection workshops={component.items ?? []} initialFilter="ALL" />
        </View>
      );

    default:
      // Unknown component type from a newer server. Skip it silently.
      return null;
  }
}

/** CommonComponentsKt.SectionHeader: serif title, coral text action. */
function SectionHeader({
  title,
  actionLabel = null,
  onAction,
}: {
  title: string;
  actionLabel?: string | null;
  onAction?: () => void;
}) {
  return (
    <View style={styles.header}>
      {/* Two lines: the design's own headings ("Sessions for neck & shoulder
          release") don't fit one line beside "See all" on a normal phone. */}
      <Text style={styles.headerTitle} numberOfLines={2}>
        {title}
      </Text>
      {actionLabel && onAction ? (
        <Pressable onPress={onAction} hitSlop={8} accessibilityRole="button">
          <Text style={styles.headerAction}>{actionLabel}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  heroItem: { paddingHorizontal: GUTTER, paddingVertical: 6 },
  tileItem: { paddingHorizontal: GUTTER, paddingVertical: 14 },
  workshops: { paddingTop: 10 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    paddingHorizontal: GUTTER,
    paddingVertical: 12,
  },
  headerTitle: {
    flex: 1,
    fontFamily: FONTS.serif,
    fontSize: 18,
    lineHeight: 22,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  headerAction: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    fontWeight: '700',
    color: colors.coralBrand,
  },
  railContent: { paddingHorizontal: GUTTER, gap: 12 },
});
