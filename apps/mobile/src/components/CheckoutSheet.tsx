import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Linking,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { Ionicons, MaterialIcons } from '@expo/vector-icons';
import { useQueryClient } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { CreateOrderRequest, OrderStatusResponse } from '@th/types';
import { invalidateAfterPurchase, useCheckout } from '@/api/hooks';
import { clearPendingOrder, getPendingOrder, purchaseKey } from '@/lib/pendingOrders';
import { ApiRequestError, api } from '@/api/client';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, radii } from '@/theme';
import { formatPaise } from '@/lib/time';
import { SUPPORT_WHATSAPP_URL } from '@/lib/links';

/**
 * Shared confirm-and-pay sheet for every purchase in the app: yoga
 * subscription, marathon registration, Premium upgrade and paid workshops.
 *
 * The amount shown here is a DISPLAY estimate. The server re-prices the order
 * from scratch and charges its own number — the client never sends a price
 * (docs/04 T8). If the two ever disagree, the server is right.
 *
 * Every way a payment can end has its own words, because "something went
 * wrong" after money moved is the worst sentence a checkout can say:
 *   granted           → you're all set
 *   FAILED            → not charged, try again
 *   PAID_NOT_GRANTED  → paid, but the seat went while paying → refund flagged
 *   still settling    → paid, access being confirmed (Pay stays off: no double charge)
 *   refused up front  → a human sentence per server code
 *
 * Visual language: the design's ModalBottomSheet (PaywallSheetKt) — PaperWhite,
 * 28dp top corners, 36×4 handle, 20dp sides, CORAL pill + close, serif title,
 * 52dp R14 coral CTA.
 */

export interface CheckoutItem {
  request: CreateOrderRequest;
  title: string;
  subtitle: string;
  displayPaise: number;
}

/** Turns server refusal codes into something a person can act on. */
const REFUSAL_COPY: Record<string, string> = {
  ALREADY_REGISTERED: 'You’re already registered for this — check your races or workshops.',
  ALREADY_PREMIUM: 'You already hold a Premium entry for this race.',
  SOLD_OUT: 'Premium has sold out for this distance. Classic entries may still be open.',
  REGISTRATION_CLOSED: 'Registrations for this race have closed.',
  UNAVAILABLE: 'This isn’t available to buy right now.',
  WORKSHOP_FULL: 'This workshop is full.',
  WORKSHOP_ENDED: 'This workshop has already ended.',
  FREE_FOR_YOU: 'This workshop is free with your membership — register without paying.',
  NOT_REGISTERED: 'You need a registration for this race before you can upgrade it.',
  UNKNOWN_PLAN: 'This plan isn’t available any more.',
};

/** Shown on the referral field itself — they're the user's to fix before paying. */
const CODE_COPY: Record<string, string> = {
  INVALID_REFERRAL_CODE: 'That referral code isn’t valid. Check it, or leave the field empty.',
  OWN_REFERRAL_CODE: 'That’s your own code — use a friend’s, or leave the field empty.',
};

function refusalText(err: Error): string {
  if (err instanceof ApiRequestError) {
    if (err.status === 0) return err.body.message; // offline / timeout — already user-facing
    const known = REFUSAL_COPY[err.body.code];
    if (known) return known;
    if (err.status >= 500) return 'Something went wrong on our side. You have not been charged.';
    return 'This couldn’t be bought right now. You have not been charged.';
  }
  // e.g. "Live payments are not enabled in this build." — already a sentence.
  return err.message || 'Payment could not be completed.';
}

type Outcome = 'IDLE' | 'GRANTED' | 'FAILED' | 'REFUND' | 'SETTLING';

/** What the user now has, in words that fit what they bought. */
function successLine(item: CheckoutItem): string {
  switch (item.request.productType) {
    case 'MARATHON_REGISTRATION':
      return `You’re registered for ${item.title}. Your bib and race pass appear on the race page once allocated.`;
    case 'PREMIUM_UPGRADE':
      return 'Your entry is now Premium VIP.';
    case 'WORKSHOP':
      return `Your seat for ${item.title} is confirmed. The join link appears here before it starts.`;
    default:
      return `${item.title} is now active on your account.`;
  }
}

function outcomeOf(status: OrderStatusResponse | null | undefined): Outcome {
  if (!status) return 'IDLE';
  if (status.entitlementGranted) return 'GRANTED';
  if (status.status === 'FAILED') return 'FAILED';
  if (status.status === 'PAID_NOT_GRANTED') return 'REFUND';
  return 'SETTLING';
}

export function CheckoutSheet({
  item,
  onClose,
  onSuccess,
}: {
  item: CheckoutItem | null;
  onClose: () => void;
  onSuccess?: () => void;
}) {
  const checkout = useCheckout();
  const qc = useQueryClient();
  const insets = useSafeAreaInsets();

  const isRegistration = item?.request.productType === 'MARATHON_REGISTRATION';
  const [code, setCode] = useState('');
  // A manual re-check of an order that was still settling when polling stopped.
  const [recheck, setRecheck] = useState<OrderStatusResponse | null>(null);
  const [rechecking, setRechecking] = useState(false);
  const [recheckFailed, setRecheckFailed] = useState(false);

  // Each new purchase starts clean (a code typed for one race isn't another's)
  // — unless an earlier payment for the same thing never confirmed: then the
  // sheet reopens on that order, re-checking it, with Pay held back.
  useEffect(() => {
    if (item) {
      setCode(item.request.referralCode ?? '');
      setRecheckFailed(false);
      const earlier = getPendingOrder(purchaseKey(item.request));
      if (earlier) {
        setRecheck({ orderId: earlier.orderId, status: 'PENDING', entitlementGranted: false });
        void refresh(earlier.orderId);
      } else {
        setRecheck(null);
      }
    }
    // `refresh` is recreated each render; only a new item should trigger this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item]);

  const status = recheck ?? checkout.data ?? null;
  const outcome = outcomeOf(status);
  const errCode = checkout.error instanceof ApiRequestError ? checkout.error.body.code : '';
  const codeError = CODE_COPY[errCode] ?? null;
  const errorText = checkout.error && !codeError ? refusalText(checkout.error) : null;

  const close = () => {
    // Mid-payment the result must not be thrown away — the sheet stays until it lands.
    if (checkout.isPending) return;
    checkout.reset();
    setRecheck(null);
    onClose();
  };

  const pay = () => {
    if (!item || checkout.isPending) return;
    const referralCode = code.trim();
    setRecheck(null);
    checkout.mutate(
      isRegistration && referralCode ? { ...item.request, referralCode } : item.request,
    );
  };

  const refresh = async (orderId = status?.orderId) => {
    if (!orderId) return;
    setRechecking(true);
    setRecheckFailed(false);
    try {
      const next = await api.get<OrderStatusResponse>(`/orders/${orderId}`);
      setRecheck(next);
      if (item && outcomeOf(next) !== 'SETTLING') clearPendingOrder(purchaseKey(item.request));
      // Same refresh the checkout hook does on settle — access just changed.
      if (next.entitlementGranted) invalidateAfterPurchase(qc);
    } catch {
      setRecheckFailed(true);
    } finally {
      setRechecking(false);
    }
  };

  const price = item ? formatPaise(item.displayPaise) : '';

  return (
    <Modal visible={item !== null} transparent animationType="slide" onRequestClose={close}>
      {/* 'padding' on both: with Android edge-to-edge the window no longer
          resizes for the keyboard, so the referral field would sit under it. */}
      <KeyboardAvoidingView style={styles.fill} behavior="padding">
        <Pressable style={styles.scrim} onPress={close} accessibilityLabel="Close" />
        <View style={[styles.sheet, { paddingBottom: 8 + insets.bottom }]}>
          <View style={styles.handle} />

          {outcome === 'GRANTED' ? (
            <View style={styles.result}>
              <View style={[styles.resultIcon, { backgroundColor: colors.liveEmerald }]}>
                <Ionicons name="checkmark" size={30} color={colors.paperWhite} />
              </View>
              <Text style={[styles.title, styles.centerText]}>You&rsquo;re all set</Text>
              <Text style={[styles.body, styles.centerText]}>{item ? successLine(item) : ''}</Text>
              <Pressable
                // Full width like Pay — the centred column would shrink it to its label.
                style={[styles.cta, styles.stretch]}
                onPress={() => {
                  close();
                  onSuccess?.();
                }}
                accessibilityRole="button"
              >
                <Text style={styles.ctaLabel}>Continue</Text>
              </Pressable>
            </View>
          ) : outcome === 'REFUND' ? (
            <View style={styles.result}>
              <View style={[styles.resultIcon, { backgroundColor: colors.amberWarn }]}>
                <MaterialIcons name="currency-rupee" size={28} color={colors.paperWhite} />
              </View>
              <Text style={[styles.title, styles.centerText]}>Your refund is on its way</Text>
              <Text style={[styles.body, styles.centerText]}>
                Payment received, but {item?.title ?? 'this'} sold out or closed while you were
                paying. We&rsquo;ve flagged a full refund — it reaches you in 5–7 working days.
              </Text>
              <Pressable
                style={[styles.cta, styles.stretch]}
                onPress={close}
                accessibilityRole="button"
              >
                <Text style={styles.ctaLabel}>Done</Text>
              </Pressable>
              <Pressable
                onPress={() => void Linking.openURL(SUPPORT_WHATSAPP_URL)}
                hitSlop={8}
                accessibilityRole="link"
              >
                <Text style={styles.link}>Contact support</Text>
              </Pressable>
            </View>
          ) : (
            <>
              <View style={styles.headRow}>
                <TagPill label="Confirm purchase" tone="CORAL" />
                {!checkout.isPending ? (
                  <Pressable
                    onPress={close}
                    style={styles.closeBtn}
                    hitSlop={8}
                    accessibilityRole="button"
                    accessibilityLabel="Close"
                  >
                    <MaterialIcons name="close" size={24} color={colors.textMuted} />
                  </Pressable>
                ) : null}
              </View>
              <Text style={[styles.title, { marginTop: 10 }]}>{item?.title}</Text>
              <Text style={[styles.body, { marginTop: 4 }]}>{item?.subtitle}</Text>

              {/* Refer & Win (§8.3): a friend's code, credited to them once this is paid. */}
              {isRegistration && outcome === 'IDLE' ? (
                <View style={styles.codeBlock}>
                  <Text style={styles.fieldLabel}>Referral code (optional)</Text>
                  <TextInput
                    value={code}
                    onChangeText={(t) => {
                      setCode(t.toUpperCase().replace(/[^A-Z0-9-]/g, ''));
                      if (codeError) checkout.reset();
                    }}
                    placeholder="A friend’s code, e.g. TH4F2KQ"
                    placeholderTextColor={colors.textMuted}
                    autoCapitalize="characters"
                    autoCorrect={false}
                    maxLength={16}
                    editable={!checkout.isPending}
                    style={[styles.input, codeError ? styles.inputError : null]}
                    accessibilityLabel="Referral code, optional"
                  />
                  {codeError ? (
                    <Text style={styles.fieldError}>{codeError}</Text>
                  ) : (
                    <Text style={styles.fieldHint}>
                      Your friend earns a lucky-draw entry when you register.
                    </Text>
                  )}
                </View>
              ) : null}

              <View style={styles.totalRow}>
                <Text style={styles.totalLabel}>Total</Text>
                <Text style={styles.total}>{price}</Text>
              </View>
              <Text style={styles.fine}>
                Inclusive of applicable taxes. Final amount is confirmed at payment.
              </Text>

              {outcome === 'FAILED' ? (
                <View style={[styles.notice, styles.noticeError]}>
                  <Ionicons name="close-circle" size={18} color={colors.crimsonAlert} />
                  <Text style={[styles.noticeText, { color: colors.crimsonAlert }]}>
                    Payment didn&rsquo;t go through — you haven&rsquo;t been charged.
                  </Text>
                </View>
              ) : outcome === 'SETTLING' ? (
                <View style={[styles.notice, styles.noticeInfo]}>
                  <ActivityIndicator size="small" color={colors.goldAccent} />
                  <View style={{ flex: 1 }}>
                    <Text style={styles.noticeText}>
                      {status?.status === 'PAID'
                        ? 'Payment received — confirming your access…'
                        : 'Confirming your payment…'}
                    </Text>
                    <Text style={styles.noticeSub}>
                      {recheckFailed
                        ? 'Couldn’t check just now. Try Refresh in a moment.'
                        : 'This can take a minute. You won’t be charged again.'}
                    </Text>
                  </View>
                </View>
              ) : errorText ? (
                <View style={[styles.notice, styles.noticeError]}>
                  <Ionicons name="alert-circle" size={18} color={colors.crimsonAlert} />
                  <Text style={[styles.noticeText, { color: colors.crimsonAlert }]}>{errorText}</Text>
                </View>
              ) : null}

              {outcome === 'FAILED' ? (
                <Pressable style={styles.cta} onPress={pay} accessibilityRole="button">
                  <Text style={styles.ctaLabel}>Try again</Text>
                </Pressable>
              ) : (
                <Pressable
                  // While a paid order settles, Pay stays off — a second tap would charge twice.
                  style={[styles.cta, (checkout.isPending || outcome === 'SETTLING') && styles.ctaBusy]}
                  disabled={checkout.isPending || outcome === 'SETTLING' || !item}
                  onPress={pay}
                  accessibilityRole="button"
                  accessibilityState={{ busy: checkout.isPending, disabled: outcome === 'SETTLING' }}
                >
                  {checkout.isPending ? (
                    <ActivityIndicator color={colors.paperWhite} />
                  ) : (
                    <Text style={styles.ctaLabel}>Pay {price}</Text>
                  )}
                </Pressable>
              )}

              {outcome === 'SETTLING' ? (
                <Pressable
                  style={styles.secondary}
                  onPress={() => void refresh()}
                  disabled={rechecking}
                  accessibilityRole="button"
                >
                  {rechecking ? (
                    <ActivityIndicator color={colors.textPrimary} />
                  ) : (
                    <Text style={styles.secondaryLabel}>Refresh</Text>
                  )}
                </Pressable>
              ) : null}

              {/* Visible marker that no real money moves in this build. */}
              <View style={styles.testMode}>
                <Ionicons name="flask-outline" size={12} color={colors.amberWarn} />
                <Text style={styles.testModeText}>Test mode — no real payment is taken</Text>
              </View>
            </>
          )}
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  // M3 ModalBottomSheet scrim: 32% black.
  scrim: { flex: 1, backgroundColor: 'rgba(0,0,0,0.32)' },
  sheet: {
    backgroundColor: colors.paperWhite,
    borderTopLeftRadius: radii.sheet,
    borderTopRightRadius: radii.sheet,
    paddingHorizontal: 20,
  },
  // Drag handle: 36×4, RCS 2, BorderRule, 10dp above and below.
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.borderRule,
    marginVertical: 10,
  },
  headRow: {
    marginTop: 8,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  closeBtn: { width: 28, height: 28, alignItems: 'center', justifyContent: 'center' },
  title: {
    fontFamily: FONTS.serif,
    fontSize: 24,
    lineHeight: 29,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  body: { fontFamily: FONTS.sans, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
  centerText: { textAlign: 'center' },
  stretch: { alignSelf: 'stretch' },

  codeBlock: { marginTop: 16, gap: 6 },
  fieldLabel: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.textPrimary },
  input: {
    height: 52,
    borderWidth: 1,
    borderColor: colors.borderRule,
    borderRadius: radii.md,
    paddingHorizontal: 14,
    fontFamily: FONTS.sans,
    fontSize: 15,
    letterSpacing: 1,
    color: colors.textPrimary,
  },
  inputError: { borderColor: colors.crimsonAlert },
  fieldError: { fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: 16, color: colors.crimsonAlert },
  fieldHint: { fontFamily: FONTS.sans, fontSize: 11, lineHeight: 15, color: colors.textMuted },

  totalRow: {
    marginTop: 16,
    paddingTop: 14,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.borderRule,
    flexDirection: 'row',
    alignItems: 'center',
  },
  totalLabel: { flex: 1, fontFamily: FONTS.sans, fontSize: 13, color: colors.textSecondary },
  total: {
    fontFamily: FONTS.serif,
    fontSize: 22,
    lineHeight: 27,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  fine: { marginTop: 4, fontFamily: FONTS.sans, fontSize: 11, lineHeight: 15, color: colors.textMuted },

  notice: {
    marginTop: 14,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    borderRadius: 10,
    padding: 12,
  },
  noticeError: { backgroundColor: colors.coralTint },
  noticeInfo: { backgroundColor: colors.goldTint },
  noticeText: {
    flex: 1,
    fontFamily: FONTS.sans,
    fontSize: 12.5,
    lineHeight: 17,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  noticeSub: { marginTop: 2, fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: 16, color: colors.textSecondary },

  // PaywallSheet CTA: Button(h52, RCS 14, CoralBrand), 14 Bold white.
  cta: {
    marginTop: 16,
    height: 52,
    borderRadius: radii.option,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  ctaBusy: { opacity: 0.7 },
  ctaLabel: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '700', color: colors.paperWhite },
  secondary: {
    marginTop: 8,
    height: 46,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    alignItems: 'center',
    justifyContent: 'center',
  },
  secondaryLabel: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.textPrimary },

  // Fine print under the CTA: 10sp, padding 8 / 12, centred.
  testMode: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
    paddingTop: 8,
    paddingBottom: 12,
  },
  testModeText: { fontFamily: FONTS.sans, fontSize: 10, fontWeight: '600', color: colors.amberWarn },

  result: { alignItems: 'center', gap: 12, paddingTop: 16, paddingBottom: 12 },
  resultIcon: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
  link: {
    fontFamily: FONTS.sans,
    fontSize: 13,
    fontWeight: '700',
    color: colors.coralBrand,
    textDecorationLine: 'underline',
  },
});
