import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { MaterialIcons } from '@expo/vector-icons';
import type { ComponentProps } from 'react';
import type { Article, Reel, UserQuote, YogaSession } from '@th/types';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, radii } from '@/theme';

/**
 * The six card templates from PRD §6.3, distinct by shape and behaviour:
 *
 *   Video      landscape, horizontal scroll   → in-app playback
 *   Reel       portrait, horizontal scroll    → in-app reel player
 *   Article    vertical, image over headline  → opens article
 *   Quote      square                         → static, NO tap
 *   EntryTile  wide, single, full-bleed       → one action
 *   PromoStrip wide, short, under the hero    → one action, server-controlled
 *
 * Every value below is lifted from the design's CommonComponentsKt /
 * HomeScreenKt (decompiled Compose, assets/design-reference). Where the design
 * leaves a Text without a lineHeight it inherits M3's bodyLarge (15/22), but
 * Compose trims that extra leading off the first and last line — so single-line
 * text here sets no lineHeight (the font's own height matches), and text that
 * usually wraps gets a lineHeight that reproduces the design's block height.
 */

type IconName = ComponentProps<typeof MaterialIcons>['name'];

// ── Card image — the design's ThCardImage ───────────────────────────────────

export interface Gradient {
  colors: readonly [string, string];
  /** Compose Brush.linearGradient (default) is diagonal, top-left → bottom-right. */
  dir?: 'diagonal' | 'vertical' | 'horizontal';
}

const DIRS = {
  diagonal: { start: { x: 0, y: 0 }, end: { x: 1, y: 1 } },
  vertical: { start: { x: 0, y: 0 }, end: { x: 0, y: 1 } },
  horizontal: { start: { x: 0, y: 0 }, end: { x: 1, y: 0 } },
} as const;

/** ThCardImage defaults: plumDeep → carbon950 fallback, transparent → 65% black scrim. */
const DEFAULT_FALLBACK: Gradient = { colors: [colors.plumDeep, colors.carbon950], dir: 'vertical' };
const DEFAULT_SCRIM: Gradient = { colors: ['transparent', 'rgba(0,0,0,0.65)'], dir: 'vertical' };

/**
 * Three stacked layers, exactly as ThCardImage draws them: a brand gradient
 * (what you see when there is no photo, or while it loads / if it fails), the
 * photo, then a scrim so white text on top stays legible on any photo.
 * Fills its parent; the parent clips the corners.
 */
export function CardImage({
  uri,
  fallback = DEFAULT_FALLBACK,
  scrim = DEFAULT_SCRIM,
}: {
  uri?: string | null;
  fallback?: Gradient;
  scrim?: Gradient | null;
}) {
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      <LinearGradient
        colors={fallback.colors}
        {...DIRS[fallback.dir ?? 'diagonal']}
        style={StyleSheet.absoluteFill}
      />
      {uri && uri.trim() ? (
        <Image source={{ uri }} style={StyleSheet.absoluteFill} resizeMode="cover" />
      ) : null}
      {scrim ? (
        <LinearGradient
          colors={scrim.colors}
          {...DIRS[scrim.dir ?? 'vertical']}
          style={StyleSheet.absoluteFill}
        />
      ) : null}
    </View>
  );
}

// ── Video — VideoLandscapeCard ──────────────────────────────────────────────

/**
 * The design picks a placeholder gradient per session by `id.hashCode()` so a
 * rail without photos still reads as distinct cards: plum, sage, carbon, gold.
 */
const PLUM_PAIR = [colors.plumDeep, colors.plumBrand] as const;
const VIDEO_FALLBACKS: readonly (readonly [string, string])[] = [
  PLUM_PAIR,
  [colors.sageBrand, colors.sageSecondary],
  [colors.carbon900, colors.carbon700],
  ['#8A4A12', colors.goldAccent],
];

/** Java/Kotlin String.hashCode, so the same id gets the same colour as the design. */
function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

const SCRIM_25_75: Gradient = { colors: ['rgba(0,0,0,0.25)', 'rgba(0,0,0,0.75)'] };

export function VideoCard({
  session,
  locked,
  onPress,
  width,
}: {
  session: YogaSession;
  locked: boolean;
  onPress: () => void;
  /** Overrides the rail width — grids size cards to their columns. */
  width?: number;
}) {
  const fallback = VIDEO_FALLBACKS[javaHash(session.id ?? '') % VIDEO_FALLBACKS.length] ?? PLUM_PAIR;
  return (
    <Pressable
      onPress={onPress}
      style={[styles.videoCard, width !== undefined && { width }]}
      accessibilityRole="button"
      accessibilityLabel={`${session.title}, ${session.durationMinutes} minutes${locked ? ', subscriber only' : ''}`}
    >
      <View style={styles.videoThumb}>
        <CardImage uri={session.imageUrl} fallback={{ colors: fallback }} scrim={SCRIM_25_75} />

        {/* Top-start badge: FREE wins; a paid session the user can't play says so. */}
        <View style={styles.videoBadge}>
          {session.isFree ? (
            <TagPill label="Free" tone="CORAL" />
          ) : locked ? (
            <View style={styles.lockPill}>
              <MaterialIcons name="lock" size={10} color={colors.paperWhite} />
              <Text style={styles.lockText}>SUBSCRIBER</Text>
            </View>
          ) : null}
        </View>

        <View style={styles.center} pointerEvents="none">
          <View style={styles.videoPlay}>
            <MaterialIcons name="play-arrow" size={22} color={colors.carbon900} />
          </View>
        </View>

        <View style={styles.durationChip}>
          <Text style={styles.durationText}>{session.durationMinutes} min</Text>
        </View>
      </View>

      <Text style={styles.videoTitle} numberOfLines={1}>
        {session.title}
      </Text>
      <Text style={styles.videoMeta} numberOfLines={1}>
        {session.instructor.name} · {session.level}
      </Text>
    </Pressable>
  );
}

// ── Reel — ReelPortraitCard ─────────────────────────────────────────────────

/** 45 → "0:45", 125 → "2:05". The design's "N plays" has no data behind it. */
function formatReelDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '';
  const s = Math.round(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export function ReelCard({ reel, onPress }: { reel: Reel; onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      style={styles.reelCard}
      accessibilityRole="button"
      accessibilityLabel={`Play reel by ${reel.instructorName}`}
    >
      <View style={styles.reelThumb}>
        <CardImage
          uri={reel.thumbnailUrl}
          fallback={{ colors: [colors.carbon800, colors.carbon950], dir: 'vertical' }}
          scrim={SCRIM_25_75}
        />
        <View style={styles.reelInner}>
          <TagPill label="Reel" tone="CORAL" />
          <View style={styles.reelPlay}>
            <MaterialIcons name="play-arrow" size={18} color={colors.paperWhite} />
          </View>
          <Text style={styles.reelDuration}>{formatReelDuration(reel.durationSeconds)}</Text>
        </View>
      </View>
      <Text style={styles.reelName} numberOfLines={1}>
        {reel.instructorName}
      </Text>
      <Text style={styles.reelHandle} numberOfLines={1}>
        {reel.instructorHandle}
      </Text>
    </Pressable>
  );
}

// ── Article — ArticleWideCard ───────────────────────────────────────────────

export function ArticleCard({ article, onPress }: { article: Article; onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      style={styles.articleCard}
      accessibilityRole="link"
      accessibilityLabel={`${article.title}. ${article.source}, ${article.readTimeMinutes} minute read`}
    >
      <View style={styles.articleImage}>
        <CardImage
          uri={article.imageUrl}
          fallback={{ colors: ['#1F2A4D', '#3A4A7A'] }}
          scrim={{ colors: ['rgba(0,0,0,0.25)', 'rgba(0,0,0,0.7)'] }}
        />
        {article.category ? (
          <View style={styles.articleTag}>
            <TagPill label={article.category} tone="NEUTRAL" />
          </View>
        ) : null}
      </View>
      <View style={styles.articleBody}>
        <Text style={styles.articleSource} numberOfLines={1}>
          {article.source.toUpperCase()}
        </Text>
        <Text style={styles.articleTitle} numberOfLines={2}>
          {article.title}
        </Text>
        <Text style={styles.articleRead}>{article.readTimeMinutes} min read</Text>
      </View>
    </Pressable>
  );
}

// ── Quote — QuoteSquareCard. Read-only: no link, no video, no tap (§6.3 rail 6).

export function QuoteCard({ quote }: { quote: UserQuote }) {
  return (
    <View style={styles.quoteCard} accessible accessibilityRole="text">
      <Text style={styles.quoteText} numberOfLines={4}>
        {`“${quote.quote}”`}
      </Text>
      <View>
        <Text style={styles.quoteAuthor} numberOfLines={1}>
          {quote.author}
        </Text>
        <Text style={styles.quoteRole} numberOfLines={1}>
          {quote.role}
        </Text>
      </View>
    </View>
  );
}

// ── Entry tile — RunTrackerEntryTile ────────────────────────────────────────

export function EntryTile({
  title,
  subtitle,
  imageUrl,
  icon = 'directions-run',
  onPress,
}: {
  title: string;
  subtitle: string;
  imageUrl?: string | null;
  icon?: IconName;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={styles.entryTile}
      accessibilityRole="button"
      accessibilityLabel={`${title}. ${subtitle}`}
    >
      {/* Navy wash left → right: dense behind the text, lighter over the photo. */}
      <CardImage
        uri={imageUrl}
        fallback={{ colors: ['#1E284A', '#2C3B68'] }}
        scrim={{ colors: ['rgba(17,23,46,0.9)', 'rgba(30,40,74,0.75)'], dir: 'horizontal' }}
      />
      <View style={styles.entryLead}>
        <View style={styles.entryIcon}>
          <MaterialIcons name={icon} size={26} color={colors.paperWhite} />
        </View>
        <View style={styles.entryText}>
          <Text style={styles.entryTitle} numberOfLines={1}>
            {title}
          </Text>
          <Text style={styles.entrySub} numberOfLines={2}>
            {subtitle}
          </Text>
        </View>
      </View>
      <MaterialIcons name="chevron-right" size={24} color="rgba(255,255,255,0.8)" />
    </Pressable>
  );
}

// ── Promo strip — narrower and shorter than the hero, deliberately ad-like ──

/** Design: plum for everyone, gold for members who hold BOTH yoga and a race. */
const PROMO_PLUM = [colors.plumDeep, colors.plumBrand] as const;
const PROMO_GOLD = ['#7A4A12', colors.goldAccent] as const;

export function PromoStrip({
  title,
  subtitle,
  ctaLabel,
  backgroundColor,
  imageUrl,
  gold = false,
  onPress,
}: {
  title: string;
  subtitle: string | null;
  ctaLabel: string;
  backgroundColor: string | null;
  imageUrl: string | null;
  /** The design's "holds both products" variant. */
  gold?: boolean;
  onPress: () => void;
}) {
  const base = gold ? PROMO_GOLD : PROMO_PLUM;
  // §6.2: the strip is server-controlled — a campaign colour, when sent,
  // replaces the gradient's start so the brand end still ties it to the app.
  const stops: readonly [string, string] = [backgroundColor ?? base[0], base[1]];
  return (
    <Pressable
      onPress={onPress}
      style={styles.promo}
      accessibilityRole="button"
      accessibilityLabel={`${title}. ${ctaLabel}`}
    >
      {imageUrl ? (
        <Image source={{ uri: imageUrl }} style={StyleSheet.absoluteFill} resizeMode="cover" />
      ) : null}
      <LinearGradient
        colors={stops}
        {...DIRS.diagonal}
        // A campaign photo shows through faintly; the gradient keeps the white text legible.
        style={[StyleSheet.absoluteFill, imageUrl ? { opacity: 0.88 } : null]}
      />
      <View style={styles.promoText}>
        <Text style={styles.promoTitle} numberOfLines={2}>
          {title}
        </Text>
        {subtitle ? (
          <Text style={styles.promoSub} numberOfLines={2}>
            {subtitle}
          </Text>
        ) : null}
      </View>
      <View style={styles.promoCta}>
        {/* Title Case as authored ("Book Free") — the design does not upper-case it. */}
        <Text style={styles.promoCtaText} numberOfLines={1}>
          {ctaLabel}
        </Text>
      </View>
    </Pressable>
  );
}

const WHITE_80 = 'rgba(255,255,255,0.8)';
/** #99000000 — the design's chip backing over photos. */
const CHIP_BG = 'rgba(0,0,0,0.6)';

const styles = StyleSheet.create({
  center: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center' },

  // Video — no card surface: the thumbnail IS the card, text sits on the canvas.
  videoCard: { width: 200 },
  videoThumb: {
    height: 115,
    borderRadius: radii.lg,
    overflow: 'hidden',
    backgroundColor: colors.surfaceSand,
  },
  videoBadge: { position: 'absolute', top: 8, left: 8 },
  lockPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
    backgroundColor: CHIP_BG,
    borderRadius: radii.tag,
    paddingHorizontal: 6,
    paddingVertical: 3,
  },
  lockText: {
    fontFamily: FONTS.sans,
    fontSize: 8.5,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  videoPlay: {
    width: 36,
    height: 36,
    borderRadius: 18,
    // #DDFFFFFF
    backgroundColor: 'rgba(255,255,255,0.867)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  durationChip: {
    position: 'absolute',
    right: 8,
    bottom: 8,
    backgroundColor: CHIP_BG,
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
  },
  durationText: {
    fontFamily: FONTS.sans,
    fontSize: 10,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  videoTitle: {
    marginTop: 6,
    fontFamily: FONTS.sans,
    fontSize: 13,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  videoMeta: { fontFamily: FONTS.sans, fontSize: 11, color: colors.textMuted },

  // Reel
  reelCard: { width: 115 },
  reelThumb: {
    height: 180,
    borderRadius: radii.lg,
    overflow: 'hidden',
    backgroundColor: colors.carbon900,
  },
  reelInner: { ...StyleSheet.absoluteFill, padding: 10, justifyContent: 'space-between' },
  reelPlay: {
    alignSelf: 'center',
    width: 32,
    height: 32,
    borderRadius: 16,
    // #44FFFFFF
    backgroundColor: 'rgba(255,255,255,0.267)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  reelDuration: {
    fontFamily: FONTS.sans,
    fontSize: 10,
    fontWeight: '600',
    color: WHITE_80,
  },
  reelName: {
    marginTop: 4,
    fontFamily: FONTS.sans,
    fontSize: 12,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  reelHandle: { fontFamily: FONTS.sans, fontSize: 10.5, color: colors.textMuted },

  // Article — M3 outlined card: outlineVariant is borderSubtle in the design theme.
  articleCard: {
    width: 250,
    borderRadius: radii.lg,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    overflow: 'hidden',
  },
  articleImage: { height: 95, backgroundColor: colors.surfaceSand },
  articleTag: { position: 'absolute', top: 10, left: 10 },
  articleBody: { padding: 12 },
  articleSource: {
    fontFamily: FONTS.sans,
    fontSize: 9.5,
    fontWeight: '800',
    letterSpacing: 0.5,
    color: colors.coralBrand,
  },
  articleTitle: {
    marginTop: 4,
    fontFamily: FONTS.serif,
    fontSize: 14,
    lineHeight: 20,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  articleRead: {
    marginTop: 6,
    fontFamily: FONTS.sans,
    fontSize: 10,
    color: colors.textMuted,
  },

  // Quote
  quoteCard: {
    width: 190,
    height: 190,
    borderRadius: radii.lg,
    backgroundColor: colors.plumTint,
    borderWidth: 1,
    borderColor: colors.plumLine,
    padding: 14,
    justifyContent: 'space-between',
  },
  quoteText: {
    fontFamily: FONTS.serif,
    fontSize: 13.5,
    lineHeight: 18,
    fontWeight: '500',
    color: colors.plumDeep,
  },
  quoteAuthor: {
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  quoteRole: { fontFamily: FONTS.sans, fontSize: 10, color: colors.textMuted },

  // Entry tile
  entryTile: {
    borderRadius: radii.xl,
    borderWidth: 1,
    borderColor: colors.borderRule,
    overflow: 'hidden',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 18,
    backgroundColor: '#1E284A',
  },
  entryLead: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 14 },
  entryIcon: {
    width: 46,
    height: 46,
    borderRadius: 23,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  entryText: { flex: 1 },
  entryTitle: {
    fontFamily: FONTS.serif,
    fontSize: 17,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  entrySub: { fontFamily: FONTS.sans, fontSize: 11.5, color: WHITE_80 },

  // Promo strip
  promo: {
    borderRadius: radii.option,
    overflow: 'hidden',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    // Not in the design (its text column simply weights against the CTA); a
    // two-line campaign headline must never butt up against the button.
    gap: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    backgroundColor: colors.plumDeep,
  },
  promoText: { flex: 1 },
  promoTitle: {
    fontFamily: FONTS.sans,
    fontSize: 13,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  promoSub: { fontFamily: FONTS.sans, fontSize: 11, color: WHITE_80 },
  promoCta: {
    // #33FFFFFF
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderRadius: radii.sm,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  promoCtaText: {
    fontFamily: FONTS.sans,
    fontSize: 11,
    fontWeight: '700',
    color: colors.paperWhite,
  },
});
