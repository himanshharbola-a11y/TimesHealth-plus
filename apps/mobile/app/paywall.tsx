import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Animated,
  PanResponder,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useSession } from '@/api/hooks';
import { CheckoutSheet, type CheckoutItem } from '@/components/CheckoutSheet';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, radii, spacing } from '@/theme';

/**
 * Yoga paywall — the design's PaywallSheet (PaywallSheetKt), a bottom sheet
 * over whatever screen opened it. app/_layout.tsx presents this route as a
 * transparent modal, so the scrim and the sheet are drawn here.
 *
 * Checkout runs end to end against the STUB gateway. Which real rail the money
 * runs on is still a commercial decision: Apple requires In-App Purchase for a
 * digital subscription consumed in-app, and the yoga group classes do not
 * qualify for the person-to-person exemption (docs/04 §5). When that lands,
 * only useCheckout()'s gateway branch changes — not this screen.
 */

// Display prices only — the server prices the order (apps/api orders.ts
// YOGA_PLANS) and those must be kept in step with these, months included.
const ANNUAL_PAISE = 499900;
const MONTHLY_PAISE = 99900;
// Derived, so the claim can't drift from the prices (the design hardcodes
// "SAVE 50%"; against these prices it is 58%).
const ANNUAL_SAVING_PCT = Math.floor((1 - ANNUAL_PAISE / (MONTHLY_PAISE * 12)) * 100);

const PLANS = [
  {
    id: 'yoga_annual',
    label: 'Annual Membership',
    price: '₹4,999',
    paise: ANNUAL_PAISE,
    months: 12,
    period: '12 months',
    // Not the design's "Billed annually": nothing here bills again.
    sub: `₹${Math.round(ANNUAL_PAISE / 100 / 12)}/month · One payment for 12 months`,
    saving: true,
  },
  {
    id: 'yoga_monthly',
    label: 'Monthly Plan',
    price: '₹999',
    paise: MONTHLY_PAISE,
    months: 1,
    period: '1 month',
    // Not the design's "Cancel anytime before renewal": there is no renewal.
    sub: 'Try it for a month',
    saving: false,
  },
] as const;

// PaywallSheetKt's four benefits, verbatim.
const BENEFITS = [
  '8 live batches daily (join any slot, morning or evening)',
  'Full library of past session recordings',
  'Single-source attendance tracker & streaks',
  'Pranayama, mobility & masterclasses with certified masters',
];

/** ModalBottomSheet's default scrim: black at 32%. */
const SCRIM = 'rgba(0,0,0,0.32)';
/** Past this, a drag on the handle dismisses the sheet. */
const DISMISS_DRAG = 96;

const formatDate = (d: Date) =>
  d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });

/**
 * The expiry a purchase would give, mirroring the server's grant (orders.ts):
 * an unexpired membership extends from its CURRENT expiry, a lapsed one starts
 * today. Display only — the server computes the real date.
 */
function expiryAfter(currentExpiryIso: string | null, months: number): Date {
  const now = new Date();
  const current = currentExpiryIso ? new Date(currentExpiryIso) : null;
  const base = current && current > now ? current : now;
  const out = new Date(base);
  out.setMonth(out.getMonth() + months);
  return out;
}

export default function PaywallScreen() {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const { productId } = useLocalSearchParams<{ productId?: string }>();
  const selected = PLANS.find((p) => p.id === productId) ?? PLANS[0];
  const [checkout, setCheckout] = useState<CheckoutItem | null>(null);

  // Who is looking decides what this sheet is for (§3: every screen resolves
  // against the current entitlement). The Gate has already loaded this.
  const yoga = useSession().data?.entitlements.yoga ?? null;
  const member = yoga?.active === true;
  const lapsed = yoga !== null && !yoga.active;

  // ── Presentation ────────────────────────────────────────────────────────
  // The route slides up as a whole; fading the scrim in after (and out before
  // leaving) keeps it from visibly sliding with the sheet.
  const scrim = useRef(new Animated.Value(0)).current;
  const drag = useRef(new Animated.Value(0)).current;
  const closing = useRef(false);

  useEffect(() => {
    Animated.timing(scrim, { toValue: 1, duration: 250, delay: 150, useNativeDriver: true }).start();
  }, [scrim]);

  const close = useCallback(() => {
    // A double tap must not pop two screens.
    if (closing.current) return;
    closing.current = true;
    Animated.timing(scrim, { toValue: 0, duration: 120, useNativeDriver: true }).start(() => {
      if (router.canGoBack()) router.back();
      else router.replace('/');
    });
  }, [router, scrim]);

  // ModalBottomSheet can be dragged down to dismiss; so can this, by its handle.
  const handlePan = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_, g) => g.dy > 4,
        onPanResponderMove: (_, g) => drag.setValue(Math.max(0, g.dy)),
        onPanResponderRelease: (_, g) => {
          if (g.dy > DISMISS_DRAG || g.vy > 1) close();
          else Animated.spring(drag, { toValue: 0, useNativeDriver: true, bounciness: 0 }).start();
        },
        onPanResponderTerminate: () =>
          Animated.spring(drag, { toValue: 0, useNativeDriver: true, bounciness: 0 }).start(),
      }),
    [close, drag],
  );

  // ── Copy ──────────────────────────────────────────────────────────────────
  const title = member ? 'Extend your membership' : lapsed ? 'Renew TimesHealth+' : 'Join TimesHealth+';
  const cta = member
    ? `Extend by ${selected.months === 1 ? '1 month' : `${selected.months} months`}`
    : `Subscribe to ${selected.label}`;
  const newExpiry = (months: number) => formatDate(expiryAfter(yoga?.expiresAt ?? null, months));

  return (
    <View style={styles.root}>
      <Animated.View style={[StyleSheet.absoluteFill, styles.scrim, { opacity: scrim }]}>
        <Pressable
          style={StyleSheet.absoluteFill}
          onPress={close}
          accessibilityRole="button"
          accessibilityLabel="Close"
        />
      </Animated.View>

      <Animated.View
        style={[
          styles.sheet,
          // ModalBottomSheet stops short of the status bar; past ~90% it scrolls.
          { maxHeight: Math.min(height * 0.9, height - insets.top), transform: [{ translateY: drag }] },
        ]}
      >
        <View style={styles.handleZone} {...handlePan.panHandlers}>
          <View style={styles.handle} />
        </View>

        <ScrollView
          contentContainerStyle={[styles.content, { paddingBottom: spacing.md + insets.bottom }]}
          showsVerticalScrollIndicator={false}
          bounces={false}
        >
          <View style={styles.headerRow}>
            <TagPill label="MEMBER ACCESS" tone="CORAL" style={styles.rowPill} />
            <Pressable
              onPress={close}
              style={styles.close}
              hitSlop={10}
              accessibilityRole="button"
              accessibilityLabel="Close"
            >
              <Ionicons name="close" size={24} color={colors.textMuted} />
            </Pressable>
          </View>

          <Text style={styles.title}>{title}</Text>
          <Text style={styles.body}>
            Full access to 8 daily live batches, certified teachers from The Yoga Institute, and
            single-source attendance.
          </Text>

          {member && yoga ? (
            <TagPill
              label={`Active until ${formatDate(new Date(yoga.expiresAt))}`}
              tone="SAGE"
              icon="checkmark-circle"
              style={styles.status}
            />
          ) : lapsed && yoga ? (
            <TagPill
              label={`Ended ${formatDate(new Date(yoga.expiresAt))}`}
              tone="NEUTRAL"
              style={styles.status}
            />
          ) : null}

          {BENEFITS.map((b) => (
            <View key={b} style={styles.benefitRow}>
              <View style={styles.benefitDot}>
                <Ionicons name="checkmark" size={12} color={colors.sageBrand} />
              </View>
              <Text style={styles.benefitText}>{b}</Text>
            </View>
          ))}

          <View style={styles.plans}>
            {PLANS.map((p) => {
              const on = p.id === selected.id;
              return (
                <Pressable
                  key={p.id}
                  style={[styles.plan, on ? styles.planOn : styles.planOff]}
                  onPress={() => router.setParams({ productId: p.id })}
                  accessibilityRole="radio"
                  accessibilityState={{ checked: on }}
                >
                  <View style={styles.planText}>
                    <View style={styles.planTitleRow}>
                      <Text style={styles.planTitle}>{p.label}</Text>
                      {p.saving ? (
                        <TagPill
                          label={`SAVE ${ANNUAL_SAVING_PCT}%`}
                          tone="GOLD"
                          style={styles.rowPill}
                        />
                      ) : null}
                    </View>
                    <Text style={styles.planSub}>{p.sub}</Text>
                    {member ? (
                      <Text style={styles.planExpiry}>New expiry: {newExpiry(p.months)}</Text>
                    ) : null}
                  </View>
                  <Text
                    style={[styles.price, { color: p.saving ? colors.plumDeep : colors.textPrimary }]}
                  >
                    {p.price}
                  </Text>
                </Pressable>
              );
            })}
          </View>

          <Pressable
            style={styles.cta}
            onPress={() =>
              setCheckout({
                request: { productType: 'YOGA_SUBSCRIPTION', productId: selected.id },
                title: `TimesHealth+ Yoga · ${selected.label}`,
                subtitle: member
                  ? `Adds ${selected.period} to your membership — new expiry ${newExpiry(selected.months)}.`
                  : '8 live daily batches, full recording library and attendance streaks.',
                displayPaise: selected.paise,
              })
            }
            accessibilityRole="button"
          >
            <Text style={styles.ctaLabel}>{cta}</Text>
          </Pressable>

          {/* True to this build: a single payment, no automatic renewal (no
              recurring rail yet — docs/04 §5). The design's "Auto-renews until
              cancelled" line is deliberately NOT used. Change it when renewals
              are real. */}
          <Text style={styles.legal}>
            One payment for {selected.period} of access. Nothing renews automatically — renew from
            your profile when it ends.
          </Text>
        </ScrollView>
      </Animated.View>

      <CheckoutSheet
        item={checkout}
        onClose={() => setCheckout(null)}
        // Land on the tab that just changed, so the subscriber view is the
        // first thing they see.
        // Back to the tabs already underneath — replace() would stack a second copy.
        onSuccess={() => router.dismissTo('/(tabs)/yoga')}
      />
    </View>
  );
}

// Compose draws a border inside the padded box; RN lays it out. Plan cards
// subtract it so content stays 14dp from the edge whichever card is selected.
const PLAN_BORDER_ON = 1.8;
const PLAN_BORDER_OFF = 1;

// Un-styled Compose Text inherits M3 bodyLarge's 22sp line height; that is what
// the design renders for its 11.5–14sp sheet copy.
const INHERITED_LINE = 22;

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: 'flex-end' },
  scrim: { backgroundColor: SCRIM },
  sheet: {
    backgroundColor: colors.paperWhite,
    borderTopLeftRadius: radii.sheet,
    borderTopRightRadius: radii.sheet,
    overflow: 'hidden',
  },
  // The design's drag handle: 36×4, 2dp corners, 10dp above and below.
  handleZone: { alignItems: 'center', paddingVertical: spacing.lg },
  handle: { width: 36, height: 4, borderRadius: 2, backgroundColor: colors.borderRule },
  content: { paddingHorizontal: spacing['5xl'], paddingTop: spacing.md },

  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  // TagPill defaults to alignSelf flex-start, which in a row means "top".
  rowPill: { alignSelf: 'center' },
  close: { width: 28, height: 28, alignItems: 'center', justifyContent: 'center' },
  title: {
    fontFamily: FONTS.serif,
    fontSize: 24,
    lineHeight: 28,
    fontWeight: '700',
    color: colors.textPrimary,
    marginTop: spacing.lg,
  },
  body: {
    fontFamily: FONTS.sans,
    fontSize: 13,
    lineHeight: 18,
    color: colors.textSecondary,
    marginTop: spacing.xs,
    marginBottom: spacing['3xl'],
  },
  status: { marginTop: -spacing.md, marginBottom: spacing.xl },

  benefitRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, paddingVertical: spacing.xs },
  benefitDot: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: colors.sageTint,
    alignItems: 'center',
    justifyContent: 'center',
  },
  benefitText: {
    flex: 1,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: INHERITED_LINE,
    fontWeight: '500',
    color: colors.textPrimary,
  },

  plans: { gap: spacing.lg, marginTop: spacing['3xl'] },
  plan: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.xl,
    borderRadius: radii.option,
  },
  planOn: {
    backgroundColor: colors.plumTint,
    borderWidth: PLAN_BORDER_ON,
    borderColor: colors.plumBrand,
    padding: spacing.xxl - PLAN_BORDER_ON,
  },
  planOff: {
    backgroundColor: colors.canvasBg,
    borderWidth: PLAN_BORDER_OFF,
    borderColor: colors.borderRule,
    padding: spacing.xxl - PLAN_BORDER_OFF,
  },
  planText: { flex: 1 },
  planTitleRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', columnGap: spacing.sm },
  planTitle: {
    fontFamily: FONTS.sans,
    fontSize: 14,
    lineHeight: INHERITED_LINE,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  planSub: { fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: INHERITED_LINE, color: colors.textSecondary },
  planExpiry: {
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    lineHeight: 16,
    fontWeight: '700',
    color: colors.plumBrand,
  },
  price: { fontFamily: FONTS.serif, fontSize: 20, lineHeight: 25, fontWeight: '700' },

  cta: {
    height: 52,
    marginTop: spacing['5xl'],
    borderRadius: radii.option,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  ctaLabel: {
    fontFamily: FONTS.sans,
    fontSize: 14,
    lineHeight: 18,
    fontWeight: '700',
    letterSpacing: 0.2,
    color: colors.paperWhite,
  },
  legal: {
    alignSelf: 'center',
    textAlign: 'center',
    fontFamily: FONTS.sans,
    fontSize: 10,
    lineHeight: 14,
    color: colors.textMuted,
    marginTop: spacing.md,
    marginBottom: spacing.xl,
  },
});
