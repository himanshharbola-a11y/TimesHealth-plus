import { useState } from 'react';
import {
  FlatList,
  Image,
  Linking,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { Ionicons, MaterialIcons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import type { ContentResponse, Instructor, UserQuote } from '@th/types';
import { SUPPORT_WHATSAPP_URL } from '@/lib/links';
import { CardImage, QuoteCard } from '@/components/feed/Cards';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, layout, radii } from '@/theme';

/**
 * The Yoga screens' shared building blocks, plus the "about the programme"
 * blocks PRD §7 asks for:
 *   §7.1 subscriber, below the fold — instructors, The Yoga Institute, chief
 *        mentor, FAQs, support
 *   §7.2 free page — instructors, Institute, chief spiritual mentor, testimonials
 * Every value is lifted from the decompiled design (YogaScreenKt,
 * YogaSessionScreenKt, CommonComponentsKt). Blocks the PRD asks for but the
 * design never drew (instructors strip, mentor, support) reuse the design's
 * card language so they sit in the page rather than on top of it.
 */

const GUTTER = layout.screenGutter;

/** Compose FontFamily.Monospace — the design's streak and timer digits. */
export const MONO = Platform.select({ ios: 'Menlo', android: 'monospace', default: 'monospace' });

/** "140000" → "140,000" — the design's own regex `(\d)(?=(\d{3})+$)`, not en-IN lakh grouping. */
export const groupThousands = (n: number) => String(Math.round(n)).replace(/(\d)(?=(\d{3})+$)/g, '$1,');

// ── SectionHeader — CommonComponentsKt.SectionHeader ────────────────────────

/** Serif 18 SemiBold title, padding(18, 12); the optional action is CoralBrand 12 Bold. */
export function SectionHeader({
  title,
  action,
  onAction,
  style,
}: {
  title: string;
  action?: string;
  onAction?: () => void;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <View style={[styles.header, style]}>
      <Text style={styles.headerTitle} accessibilityRole="header">
        {title}
      </Text>
      {action && onAction ? (
        <Pressable onPress={onAction} hitSlop={10} accessibilityRole="link">
          <Text style={styles.headerAction}>{action}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

// ── InstructorAvatar — CommonComponentsKt.InstructorAvatarImage ─────────────

/**
 * A plumDeep → coral circle with the instructor's initials, the photo laid
 * over it — so a missing or slow photo still reads as a person, not a hole.
 */
export function InstructorAvatar({ name, uri, size }: { name: string; uri?: string | null; size: number }) {
  const initials = name
    .split(' ')
    .map((w) => w.charAt(0))
    .filter(Boolean)
    .slice(0, 2)
    .join('');
  return (
    <View style={{ width: size, height: size, borderRadius: size / 2, overflow: 'hidden' }}>
      <LinearGradient
        colors={[colors.plumDeep, colors.coralBrand]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={[StyleSheet.absoluteFill, styles.center]}
      >
        <Text style={[styles.initials, { fontSize: size * 0.38 }]}>{initials}</Text>
      </LinearGradient>
      {uri && uri.trim() ? (
        <Image source={{ uri }} style={StyleSheet.absoluteFill} resizeMode="cover" />
      ) : null}
    </View>
  );
}

// ── SalesMembershipBanner — YogaSessionScreenKt.SalesMembershipBanner ───────

const PASS_PHOTO =
  'https://images.unsplash.com/photo-1545205597-3d9d02c29597?auto=format&fit=crop&w=800&q=80';

/**
 * The "TimesHealth+ Pass" strip the design puts under the explorer hero and
 * at the end of a session's detail. Callers show it to non-members only — a
 * "Try Free" pitch to someone already paying is noise.
 */
export function SalesMembershipBanner({ onPress }: { onPress: () => void }) {
  return (
    <View style={styles.pass}>
      <CardImage
        uri={PASS_PHOTO}
        fallback={{ colors: [colors.carbon950, '#2A2033'], dir: 'horizontal' }}
        scrim={{ colors: ['rgba(18,20,23,0.92)', 'rgba(42,31,54,0.85)'], dir: 'horizontal' }}
      />
      <View style={styles.passRow}>
        <View style={{ flex: 1 }}>
          <View style={styles.passTitleRow}>
            <View style={styles.passBadge}>
              <Text style={styles.passBadgeText}>TIMESHEALTH+ PASS</Text>
            </View>
            <Text style={styles.passTitle} numberOfLines={1}>
              Unlimited Live Access
            </Text>
          </View>
          <Text style={styles.passBody}>
            Switch freely between all 8 live daily batches + 1-on-1 diet consults.
          </Text>
        </View>
        <Pressable style={styles.passBtn} onPress={onPress} accessibilityRole="button">
          <Text style={styles.passBtnText}>Try Free</Text>
        </Pressable>
      </View>
    </View>
  );
}

// ── About-the-programme blocks ──────────────────────────────────────────────

export function InstructorsStrip({ instructors }: { instructors: Instructor[] }) {
  if (instructors.length === 0) return null;
  return (
    <View style={styles.block}>
      <SectionHeader title="Your instructors" />
      <FlatList
        horizontal
        data={instructors}
        keyExtractor={(i) => i.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.rail}
        renderItem={({ item }) => (
          <View style={styles.instructor}>
            <View style={styles.instructorPhoto}>
              <CardImage uri={item.avatarUrl} scrim={null} />
            </View>
            <Text style={styles.instructorName} numberOfLines={1}>
              {item.name}
            </Text>
            <Text style={styles.instructorMeta} numberOfLines={2}>
              {item.specialty}
            </Text>
            <Text style={styles.instructorRating}>★ {item.rating.toFixed(1)}</Text>
          </View>
        )}
      />
    </View>
  );
}

/** Lambda 714378215: Spacer 14, then a plumTint/plumLine card, R16, padding 16. */
export function InstituteCard() {
  return (
    <View style={styles.institute}>
      <TagPill label="Academic partner" tone="CORAL" />
      <Text style={styles.instituteTitle}>Taught with The Yoga Institute</Text>
      <Text style={styles.instituteBody}>
        Founded 1918 — the world&rsquo;s oldest organized center. Every teacher in TH+ is certified
        through their classical curriculum.
      </Text>
    </View>
  );
}

/**
 * §7.1/§7.2 "chief (spiritual) mentor" — not drawn in the design, so it takes
 * the design's instructor-card layout (52dp avatar, name 16 Bold, title 11.5,
 * bio 12/17) from the session detail screen.
 */
export function MentorCard({ mentor }: { mentor: ContentResponse['mentor'] }) {
  return (
    <View style={styles.card}>
      <TagPill label="Chief mentor" tone="GOLD" />
      <View style={styles.mentorRow}>
        <InstructorAvatar name={mentor.name} uri={mentor.avatarUrl} size={52} />
        <View style={{ flex: 1 }}>
          <Text style={styles.mentorName}>{mentor.name}</Text>
          <Text style={styles.mentorTitle}>{mentor.title}</Text>
        </View>
      </View>
      <Text style={styles.cardBody}>{mentor.bio}</Text>
    </View>
  );
}

/** YogaScreenKt.FaqAccordion: one card per question, padding(18, 3), R12, padding 14. */
export function FaqAccordion({ faqs }: { faqs: ContentResponse['yogaFaqs'] }) {
  const [open, setOpen] = useState<number | null>(null);
  if (faqs.length === 0) return null;
  return (
    <View style={styles.faqBlock}>
      <SectionHeader title="Frequently asked questions" />
      {faqs.map((f, i) => {
        const expanded = open === i;
        return (
          <Pressable
            key={f.question}
            onPress={() => setOpen(expanded ? null : i)}
            style={styles.faq}
            accessibilityRole="button"
            accessibilityState={{ expanded }}
          >
            <View style={styles.faqHead}>
              <Text style={styles.faqQuestion}>{f.question}</Text>
              <MaterialIcons
                name={expanded ? 'keyboard-arrow-up' : 'keyboard-arrow-down'}
                size={24}
                color={colors.textMuted}
              />
            </View>
            {expanded ? <Text style={styles.faqAnswer}>{f.answer}</Text> : null}
          </Pressable>
        );
      })}
    </View>
  );
}

/** §7.1 support — the design's own "Help & Support" row copy (ProfileSheetKt). */
export function SupportCard() {
  return (
    <Pressable
      style={[styles.card, styles.supportRow]}
      onPress={() => void Linking.openURL(SUPPORT_WHATSAPP_URL)}
      accessibilityRole="button"
    >
      <View style={styles.supportIcon}>
        <Ionicons name="logo-whatsapp" size={20} color={colors.paperWhite} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.supportTitle}>Help &amp; Support</Text>
        <Text style={styles.supportSub}>FAQs, class link issues and ticket queue</Text>
      </View>
      <MaterialIcons name="chevron-right" size={20} color={colors.textMuted} />
    </Pressable>
  );
}

/** "What members say": Spacer 14, SectionHeader, LazyRow(contentPadding 18, gap 12) of QuoteSquareCard. */
export function MemberQuotes({ quotes }: { quotes: UserQuote[] }) {
  if (quotes.length === 0) return null;
  return (
    <View style={styles.block}>
      <SectionHeader title="What members say" />
      <FlatList
        horizontal
        data={quotes}
        keyExtractor={(q) => q.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.rail}
        renderItem={({ item }) => <QuoteCard quote={item} />}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', justifyContent: 'center' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: GUTTER,
    paddingVertical: 12,
  },
  headerTitle: {
    flexShrink: 1,
    fontFamily: FONTS.serif,
    fontSize: 18,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  headerAction: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.coralBrand },
  initials: { fontFamily: FONTS.sans, fontWeight: '700', color: colors.paperWhite },

  // Sales banner
  pass: {
    marginHorizontal: GUTTER,
    borderRadius: radii.lg,
    borderWidth: 1,
    // GoldAccent at 40%
    borderColor: 'rgba(201,138,22,0.4)',
    overflow: 'hidden',
  },
  passRow: { flexDirection: 'row', alignItems: 'center', padding: 14, gap: 12 },
  passTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  passBadge: { backgroundColor: colors.goldAccent, borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2 },
  passBadgeText: { fontFamily: FONTS.sans, fontSize: 8.5, fontWeight: '900', color: colors.carbon950 },
  passTitle: {
    flexShrink: 1,
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  passBody: {
    marginTop: 4,
    fontFamily: FONTS.sans,
    fontSize: 11,
    lineHeight: 15,
    color: 'rgba(255,255,255,0.8)',
  },
  passBtn: {
    backgroundColor: colors.coralBrand,
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  passBtnText: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.paperWhite },

  // About blocks
  block: { marginTop: 14 },
  rail: { paddingHorizontal: GUTTER, gap: 12 },
  instructor: { width: 120 },
  instructorPhoto: {
    width: 120,
    height: 120,
    borderRadius: radii.md,
    overflow: 'hidden',
    backgroundColor: colors.surfaceSand,
    marginBottom: 6,
  },
  instructorName: { fontFamily: FONTS.sans, fontSize: 12.5, fontWeight: '700', color: colors.textPrimary },
  instructorMeta: { fontFamily: FONTS.sans, fontSize: 11, lineHeight: 15, color: colors.textMuted },
  instructorRating: {
    marginTop: 2,
    fontFamily: FONTS.sans,
    fontSize: 11,
    fontWeight: '600',
    color: colors.goldAccent,
  },
  institute: {
    marginTop: 14,
    marginHorizontal: GUTTER,
    backgroundColor: colors.plumTint,
    borderWidth: 1,
    borderColor: colors.plumLine,
    borderRadius: radii.lg,
    padding: 16,
  },
  instituteTitle: {
    marginTop: 6,
    fontFamily: FONTS.serif,
    fontSize: 18,
    fontWeight: '700',
    color: colors.plumDeep,
  },
  instituteBody: {
    marginTop: 4,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
  },
  card: {
    marginTop: 14,
    marginHorizontal: GUTTER,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderRule,
    borderRadius: radii.lg,
    padding: 16,
  },
  cardBody: {
    marginTop: 12,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
  },
  mentorRow: { flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 10 },
  mentorName: { fontFamily: FONTS.sans, fontSize: 16, fontWeight: '700', color: colors.textPrimary },
  mentorTitle: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textSecondary },
  faqBlock: { marginTop: 16 },
  faq: {
    marginHorizontal: GUTTER,
    marginVertical: 3,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderRule,
    borderRadius: radii.md,
    padding: 14,
  },
  faqHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  faqQuestion: {
    flex: 1,
    fontFamily: FONTS.sans,
    fontSize: 13,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  faqAnswer: {
    marginTop: 6,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
  },
  supportRow: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 14, borderRadius: radii.option },
  supportIcon: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: colors.sageSecondary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  supportTitle: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.textPrimary },
  supportSub: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },
});
