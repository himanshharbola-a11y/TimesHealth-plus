import { useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  Linking,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import type { LiveWorkshop, WorkshopCategory } from '@th/types';
import { ApiRequestError } from '@/api/client';
import { useToggleWorkshop } from '@/api/hooks';
import { CheckoutSheet, type CheckoutItem } from './CheckoutSheet';
import { CardImage } from './feed/Cards';
import { TagPill, type TagPillTone } from './TagPill';
import { SUPPORT_WHATSAPP_URL } from '@/lib/links';
import { FONTS, colors, layout, radii, shadow } from '@/theme';
import { formatPaise, formatSessionDate } from '@/lib/time';

/**
 * Live workshops — in the design (LiveSessionsComponent.kt), not the PRD; in
 * scope by explicit decision.
 *
 * One section, three placements, each with a different starting filter, as in
 * the design: Home and the free Yoga page start on All, a yoga subscriber's
 * Yoga tab starts on Yoga, and the Marathon tab starts on Marathon.
 *
 * Geometry is LiveSessionsWorkshopSection's: a header row (LIVE pill + plum
 * chip), filter chips, then a VERTICAL list of full-width cards (spacedBy 14,
 * 18dp gutter). Tapping a card opens WorkshopDetailDialog; the card's own
 * button acts directly (Register / Registered ✓), as the design wires it.
 */

type Filter = 'ALL' | Extract<WorkshopCategory, 'YOGA' | 'MARATHON'>;

const CATEGORY_LABEL: Record<WorkshopCategory, string> = {
  YOGA: 'Yoga clinic',
  MARATHON: 'Marathon workshop',
  DIET: 'Nutrition masterclass',
};

const CATEGORY_TONE: Record<WorkshopCategory, TagPillTone> = {
  YOGA: 'SAGE',
  MARATHON: 'CORAL',
  DIET: 'SAGE',
};

/** The dialog's category pill — WorkshopCategory.label in the design. */
const CATEGORY_NAME: Record<WorkshopCategory, string> = {
  YOGA: 'Yoga & Therapy',
  MARATHON: 'Marathon Training',
  DIET: 'Nutrition',
};

/** Capacity badge turns coral at this many seats or fewer (design: <= 8). */
const LOW_SPOTS = 8;

export function WorkshopSection({
  workshops,
  initialFilter = 'ALL',
  showFilters = true,
}: {
  workshops: LiveWorkshop[];
  initialFilter?: Filter;
  showFilters?: boolean;
}) {
  const [filter, setFilter] = useState<Filter>(initialFilter);
  const [checkout, setCheckout] = useState<CheckoutItem | null>(null);
  // An id, not a snapshot: the dialog follows the live list as seats change.
  const [detailId, setDetailId] = useState<string | null>(null);
  const pendingCheckout = useRef<CheckoutItem | null>(null);
  const toggle = useToggleWorkshop();

  const detail = detailId ? (workshops.find((w) => w.id === detailId) ?? null) : null;

  const update = (w: LiveWorkshop) =>
    toggle.mutate(w.id, {
      onError: (e) =>
        Alert.alert(
          'Couldn’t update your seat',
          e instanceof ApiRequestError && e.status !== 0 ? e.message : 'Check your connection and try again.',
        ),
    });

  const checkoutFor = (w: LiveWorkshop): CheckoutItem => ({
    request: { productType: 'WORKSHOP', productId: w.id },
    title: w.title,
    subtitle: `${w.instructorName} · ${formatSessionDate(w.startsAt)}`,
    displayPaise: w.pricePaise ?? 0,
  });

  // A paid seat goes through checkout; the server refuses to hand one out
  // directly (402). Free-for-member seats register in place, and cancelling
  // one asks first — a mis-tap shouldn't cost someone their place.
  const handlePress = (w: LiveWorkshop, fromDialog = false) => {
    if (!w.isRegistered && w.pricePaise !== null && w.pricePaise > 0) {
      const item = checkoutFor(w);
      if (fromDialog && Platform.OS === 'ios') {
        // One modal at a time: iOS drops a sheet presented while the dialog
        // is still animating away, so checkout opens from its onDismiss.
        pendingCheckout.current = item;
        setDetailId(null);
      } else {
        setDetailId(null);
        setCheckout(item);
      }
      return;
    }
    if (w.isRegistered) {
      Alert.alert('Cancel your seat?', `You’ll give up your place in ${w.title}.`, [
        { text: 'Keep my seat', style: 'cancel' },
        {
          text: 'Cancel seat',
          style: 'destructive',
          onPress: () => {
            setDetailId(null);
            update(w);
          },
        },
      ]);
      return;
    }
    setDetailId(null);
    update(w);
  };

  const counts = useMemo(
    () => ({
      YOGA: workshops.filter((w) => w.category === 'YOGA').length,
      MARATHON: workshops.filter((w) => w.category === 'MARATHON').length,
    }),
    [workshops],
  );

  const visible = useMemo(
    () => (filter === 'ALL' ? workshops : workshops.filter((w) => w.category === filter)),
    [filter, workshops],
  );

  if (workshops.length === 0) return null;

  return (
    <View style={styles.wrap}>
      <View style={styles.head}>
        <View style={styles.headRow}>
          {/* The design's "Feature preview ·" prefix is dropped: workshops are a
              live, paid product here, and "preview" would undersell a purchase. */}
          <TagPill label="Live sessions" tone="LIVE" />
          <View style={styles.headChip}>
            <Text style={styles.headChipText}>Interactive Workshops</Text>
          </View>
        </View>
        <View style={{ height: 6 }} />
        <Text style={styles.title}>Upcoming Live Workshops</Text>
        <Text style={styles.lede}>
          Small-group clinics with certified masters from The Yoga Institute & elite marathon
          coaches. Reserve your spot below.
        </Text>
      </View>

      {showFilters ? (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.filters}
        >
          <Chip
            label={`All Workshops (${workshops.length})`}
            active={filter === 'ALL'}
            onPress={() => setFilter('ALL')}
          />
          <Chip
            label={`🧘 Yoga & Therapy (${counts.YOGA})`}
            active={filter === 'YOGA'}
            onPress={() => setFilter('YOGA')}
          />
          <Chip
            label={`🏃 Marathon Training (${counts.MARATHON})`}
            active={filter === 'MARATHON'}
            onPress={() => setFilter('MARATHON')}
          />
        </ScrollView>
      ) : null}

      <View style={{ height: 14 }} />
      <View style={styles.list}>
        {visible.length === 0 ? (
          <Text style={styles.empty}>No workshops in this category right now.</Text>
        ) : (
          visible.map((w) => (
            <WorkshopCard
              key={w.id}
              workshop={w}
              pending={toggle.isPending && toggle.variables === w.id}
              onOpen={() => setDetailId(w.id)}
              onToggle={() => handlePress(w)}
            />
          ))
        )}
      </View>

      <WorkshopDialog
        workshop={detail}
        pending={!!detail && toggle.isPending && toggle.variables === detail.id}
        onClose={() => setDetailId(null)}
        onConfirm={(w) => handlePress(w, true)}
        onDismiss={() => {
          if (!pendingCheckout.current) return;
          setCheckout(pendingCheckout.current);
          pendingCheckout.current = null;
        }}
      />

      <CheckoutSheet item={checkout} onClose={() => setCheckout(null)} />
    </View>
  );
}

function WorkshopCard({
  workshop: w,
  pending,
  onOpen,
  onToggle,
}: {
  workshop: LiveWorkshop;
  pending: boolean;
  onOpen: () => void;
  onToggle: () => void;
}) {
  return (
    <Pressable
      style={({ pressed }) => [styles.card, shadow.card, pressed && styles.pressed]}
      onPress={onOpen}
      accessibilityRole="button"
      accessibilityLabel={`${w.title}. Details`}
    >
      {/* Image 130dp: fallback #1E284A→#0F1526, overlay black .25→.82. */}
      <View style={styles.image}>
        <CardImage
          uri={w.imageUrl}
          fallback={{ colors: ['#1E284A', '#0F1526'], dir: 'vertical' }}
          scrim={{ colors: ['rgba(0,0,0,0.25)', 'rgba(0,0,0,0.82)'], dir: 'vertical' }}
        />
        <View style={styles.imageTop}>
          <TagPill label={CATEGORY_LABEL[w.category]} tone={CATEGORY_TONE[w.category]} />
          {/* "Full / Waitlist" — capacity is the edge case the design draws. */}
          <View
            style={[
              styles.spots,
              { backgroundColor: w.spotsRemaining <= LOW_SPOTS ? colors.coralBrand : 'rgba(0,0,0,0.6)' },
            ]}
          >
            <Text style={styles.spotsText}>
              {w.spotsRemaining > 0 ? `${w.spotsRemaining} spots left` : 'Full / Waitlist'}
            </Text>
          </View>
        </View>
        <View style={styles.imageBottom}>
          <View style={styles.dateRow}>
            <MaterialIcons name="calendar-month" size={13} color={colors.goldAccent} />
            <Text style={styles.dateText} numberOfLines={1}>
              {formatSessionDate(w.startsAt)}
            </Text>
          </View>
          <View style={styles.durationChip}>
            <Text style={styles.durationText} numberOfLines={1}>
              {`${w.durationMinutes} MINS · ${w.platform}`}
            </Text>
          </View>
        </View>
      </View>

      <View style={styles.body}>
        <Text style={styles.cardTitle} numberOfLines={2}>
          {w.title}
        </Text>
        <View style={{ height: 8 }} />
        <View style={styles.instructor}>
          <Image source={{ uri: w.instructorAvatarUrl }} style={styles.avatar} />
          <View style={{ flex: 1 }}>
            <View style={styles.nameRow}>
              <Text style={styles.instructorName} numberOfLines={1}>
                {w.instructorName}
              </Text>
              <MaterialIcons
                name="verified"
                size={13}
                color={colors.goldAccent}
                accessibilityLabel="Verified Master"
              />
            </View>
            <Text style={styles.instructorTitle} numberOfLines={1}>
              {w.instructorTitle}
            </Text>
          </View>
        </View>
        <View style={{ height: 10 }} />
        <View style={styles.focus}>
          <MaterialIcons
            name={w.category === 'YOGA' ? 'self-improvement' : 'directions-run'}
            size={14}
            color={colors.plumDeep}
          />
          <Text style={styles.focusText} numberOfLines={1}>
            Focus: {w.focusArea}
          </Text>
        </View>
        <View style={{ height: 12 }} />
        <View style={styles.footer}>
          <View style={{ flex: 1 }}>
            <Text style={styles.price}>
              {w.pricePaise === null ? 'Free for Members' : formatPaise(w.pricePaise)}
            </Text>
            <Text style={styles.qa}>Interactive Q&A included</Text>
          </View>
          <SeatButton workshop={w} pending={pending} onToggle={onToggle} />
        </View>
        {w.paidSeat && !w.joinUrl ? <SupportLink /> : null}
      </View>
    </Pressable>
  );
}

/**
 * The card's action, wrap-width at the right of the price row (h42, R10):
 *  - seat holder, close to start → Join (the server hands the link out then)
 *  - paid seat → "Seat confirmed": never cancelled in-app, that goes to support
 *  - free seat → "Registered ✓" (tap asks before cancelling)
 *  - else → "Register" (plumDeep for yoga, coral otherwise); full → disabled
 */
function SeatButton({
  workshop: w,
  pending,
  onToggle,
}: {
  workshop: LiveWorkshop;
  pending: boolean;
  onToggle: () => void;
}) {
  const full = !w.isRegistered && w.spotsRemaining <= 0;

  if (w.isRegistered && w.joinUrl) {
    return (
      <Pressable
        style={({ pressed }) => [styles.seatBtn, { backgroundColor: colors.liveEmerald }, pressed && styles.pressed]}
        onPress={() => void Linking.openURL(w.joinUrl!)}
        accessibilityRole="button"
      >
        <MaterialIcons name="videocam" size={16} color={colors.paperWhite} />
        <Text style={[styles.seatText, { color: colors.paperWhite }]}>Join Live Workshop</Text>
      </Pressable>
    );
  }
  if (w.paidSeat) {
    return (
      <View style={[styles.seatBtn, styles.seatRegistered]} accessible accessibilityLabel="Seat confirmed">
        <MaterialIcons name="check-circle" size={16} color={colors.liveEmerald} />
        <Text style={[styles.seatText, { color: colors.liveEmerald }]}>Seat confirmed</Text>
      </View>
    );
  }
  if (w.isRegistered) {
    return (
      <Pressable
        style={({ pressed }) => [styles.seatBtn, styles.seatRegistered, pressed && styles.pressed]}
        onPress={onToggle}
        disabled={pending}
        accessibilityRole="button"
        accessibilityLabel="Registered. Tap to cancel your seat."
      >
        {pending ? (
          <ActivityIndicator size="small" color={colors.liveEmerald} />
        ) : (
          <>
            <MaterialIcons name="check-circle" size={16} color={colors.liveEmerald} />
            <Text style={[styles.seatText, { color: colors.liveEmerald }]}>Registered ✓</Text>
          </>
        )}
      </Pressable>
    );
  }
  return (
    <Pressable
      style={({ pressed }) => [
        styles.seatBtn,
        { backgroundColor: full ? colors.surfaceSand : w.category === 'YOGA' ? colors.plumDeep : colors.coralBrand },
        pressed && styles.pressed,
      ]}
      onPress={onToggle}
      disabled={full || pending}
      accessibilityRole="button"
      accessibilityState={{ disabled: full || pending, busy: pending }}
    >
      {pending ? (
        <ActivityIndicator size="small" color={colors.paperWhite} />
      ) : full ? (
        <Text style={[styles.seatText, { color: colors.textMuted }]}>Full</Text>
      ) : (
        <>
          <MaterialIcons name="app-registration" size={16} color={colors.paperWhite} />
          <Text style={[styles.seatText, { color: colors.paperWhite }]}>Register</Text>
        </>
      )}
    </Pressable>
  );
}

function SupportLink() {
  return (
    <Pressable
      onPress={() => void Linking.openURL(SUPPORT_WHATSAPP_URL)}
      hitSlop={8}
      style={styles.supportWrap}
      accessibilityRole="link"
    >
      <Text style={styles.supportLink}>Need to cancel? Contact support</Text>
    </Pressable>
  );
}

/** WorkshopDetailDialog — an AlertDialog, R16, paper white. */
function WorkshopDialog({
  workshop: w,
  pending,
  onClose,
  onConfirm,
  onDismiss,
}: {
  workshop: LiveWorkshop | null;
  pending: boolean;
  onClose: () => void;
  onConfirm: (w: LiveWorkshop) => void;
  onDismiss: () => void;
}) {
  // Keep the last workshop on screen while the dialog fades out.
  const last = useRef<LiveWorkshop | null>(null);
  if (w) last.current = w;
  const shown = w ?? last.current;

  let confirm: { label: string; color: string; onPress: () => void } | null = null;
  if (shown) {
    const full = !shown.isRegistered && shown.spotsRemaining <= 0;
    if (shown.isRegistered && shown.joinUrl) {
      confirm = {
        label: 'Join Live Workshop',
        color: colors.liveEmerald,
        onPress: () => void Linking.openURL(shown.joinUrl!),
      };
    } else if (shown.paidSeat) {
      confirm = null; // Paid seats are cancelled through support (shown below).
    } else if (shown.isRegistered) {
      confirm = { label: 'Cancel Registration', color: colors.textMuted, onPress: () => onConfirm(shown) };
    } else if (!full) {
      confirm = {
        label:
          shown.pricePaise !== null && shown.pricePaise > 0
            ? `Reserve · ${formatPaise(shown.pricePaise)}`
            : 'Confirm Registration',
        color: colors.coralBrand,
        onPress: () => onConfirm(shown),
      };
    }
  }

  return (
    <Modal visible={!!w} transparent animationType="fade" onRequestClose={onClose} onDismiss={onDismiss}>
      <View style={styles.dialogRoot}>
        <Pressable style={styles.scrim} onPress={onClose} accessibilityRole="button" accessibilityLabel="Close" />
        {shown ? (
          <View style={styles.dialog} accessibilityViewIsModal>
            <ScrollView style={{ flexGrow: 0 }} showsVerticalScrollIndicator={false}>
              <Text style={styles.dialogTitle}>{shown.title}</Text>
              <View style={{ height: 10 }} />
              <View style={styles.dialogPills}>
                <TagPill label={CATEGORY_NAME[shown.category]} tone="CORAL" />
                <TagPill label={shown.level} tone="NEUTRAL" />
              </View>
              <Text style={styles.dialogDesc}>{shown.description}</Text>
              <View style={styles.dialogDivider} />
              <View style={styles.dialogInstructor}>
                <Image source={{ uri: shown.instructorAvatarUrl }} style={styles.dialogAvatar} />
                <View style={{ flex: 1 }}>
                  <Text style={styles.dialogName}>{shown.instructorName}</Text>
                  <Text style={styles.dialogRole}>{shown.instructorTitle}</Text>
                </View>
              </View>
              <View style={styles.schedule}>
                <Text style={styles.scheduleText}>
                  🗓️ Schedule: {formatSessionDate(shown.startsAt)} ({shown.durationMinutes} mins)
                </Text>
                {/* The join link reaches seat holders here, close to the start. */}
                <Text style={styles.scheduleLink}>
                  📍 Link: {shown.platform} (appears here for seat holders before it starts)
                </Text>
              </View>
              {shown.paidSeat && !shown.joinUrl ? (
                <View style={styles.dialogSeat}>
                  <MaterialIcons name="check-circle" size={16} color={colors.liveEmerald} />
                  <Text style={[styles.seatText, { color: colors.liveEmerald }]}>Seat confirmed</Text>
                </View>
              ) : null}
              {shown.paidSeat && !shown.joinUrl ? <SupportLink /> : null}
            </ScrollView>

            <View style={styles.dialogActions}>
              <Pressable onPress={onClose} style={styles.textBtn} accessibilityRole="button">
                <Text style={styles.textBtnLabel}>Close</Text>
              </Pressable>
              {confirm ? (
                <Pressable
                  style={({ pressed }) => [styles.confirmBtn, { backgroundColor: confirm.color }, pressed && styles.pressed]}
                  onPress={confirm.onPress}
                  disabled={pending}
                  accessibilityRole="button"
                >
                  {pending ? (
                    <ActivityIndicator size="small" color={colors.paperWhite} />
                  ) : (
                    <Text style={styles.confirmLabel}>{confirm.label}</Text>
                  )}
                </Pressable>
              ) : null}
            </View>
          </View>
        ) : null}
      </View>
    </Modal>
  );
}

/** WorkshopFilterChip: R20, border 1, padding 14×7, 12sp. */
function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <Pressable
      style={[styles.chip, active && styles.chipActive]}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
    >
      <Text style={[styles.chipText, active && styles.chipTextActive]}>{label}</Text>
    </Pressable>
  );
}

const GUTTER = layout.screenGutter;

const styles = StyleSheet.create({
  wrap: {},
  pressed: { opacity: 0.85 },
  // The section owns its gutter: parents render it full-bleed.
  head: { paddingHorizontal: GUTTER, paddingTop: 12 },
  headRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  headChip: {
    backgroundColor: colors.plumTint,
    borderRadius: radii.tag,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  headChipText: { fontFamily: FONTS.sans, fontSize: 10.5, fontWeight: '700', color: colors.plumDeep },
  title: { fontFamily: FONTS.serif, fontSize: 20, lineHeight: 25, fontWeight: '700', color: colors.textPrimary },
  lede: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
    marginTop: 2,
    marginBottom: 12,
  },
  filters: { paddingHorizontal: GUTTER, gap: 8 },
  chip: {
    borderRadius: 20,
    borderWidth: 1,
    borderColor: colors.borderRule,
    backgroundColor: colors.paperWhite,
    paddingHorizontal: 14,
    paddingVertical: 7,
  },
  chipActive: { backgroundColor: colors.plumDeep, borderColor: colors.plumDeep },
  chipText: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '500', color: colors.textPrimary },
  chipTextActive: { color: colors.paperWhite, fontWeight: '700' },

  list: { gap: 14 },
  empty: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    color: colors.textMuted,
    paddingHorizontal: GUTTER,
    paddingVertical: 12,
  },
  // Card: R18, PaperWhite, elevation 2, BorderStroke(1, BorderRule).
  card: {
    marginHorizontal: GUTTER,
    borderRadius: 18,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderRule,
    overflow: 'hidden',
  },
  image: { height: 130, justifyContent: 'space-between' },
  imageTop: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    padding: 12,
  },
  spots: { borderRadius: radii.tag, paddingHorizontal: 7, paddingVertical: 3 },
  spotsText: { fontFamily: FONTS.sans, fontSize: 10, fontWeight: '700', color: colors.paperWhite },
  imageBottom: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  dateRow: { flexDirection: 'row', alignItems: 'center', gap: 5, flexShrink: 1 },
  dateText: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '700', color: colors.paperWhite },
  durationChip: {
    backgroundColor: 'rgba(0,0,0,0.5)',
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
    flexShrink: 1,
  },
  durationText: { fontFamily: FONTS.sans, fontSize: 9, color: 'rgba(255,255,255,0.9)' },

  body: { padding: 14 },
  cardTitle: { fontFamily: FONTS.serif, fontSize: 16, lineHeight: 22, fontWeight: '700', color: colors.textPrimary },
  instructor: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  avatar: { width: 28, height: 28, borderRadius: 14, backgroundColor: colors.surfaceSand },
  nameRow: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  instructorName: {
    flexShrink: 1,
    fontFamily: FONTS.sans,
    fontSize: 12.5,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  instructorTitle: { fontFamily: FONTS.sans, fontSize: 10.5, color: colors.textMuted },
  focus: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.surfaceSand,
    borderRadius: radii.sm,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  focusText: { flex: 1, fontFamily: FONTS.sans, fontSize: 11, fontWeight: '600', color: colors.plumDeep },
  footer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10 },
  // Design: coralBrand at 11.5sp.
  price: { fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: 15, fontWeight: '700', color: colors.coralBrand },
  qa: { fontFamily: FONTS.sans, fontSize: 10, color: colors.textMuted },
  seatBtn: {
    height: 42,
    borderRadius: 10,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingHorizontal: 16,
    minWidth: 96,
  },
  seatRegistered: {
    backgroundColor: colors.liveEmeraldTint,
    borderWidth: 1,
    borderColor: 'rgba(16,185,129,0.4)',
    paddingHorizontal: 14,
  },
  seatText: { fontFamily: FONTS.sans, fontSize: 12.5, fontWeight: '700' },
  supportWrap: { alignSelf: 'flex-end', marginTop: 8 },
  supportLink: {
    fontFamily: FONTS.sans,
    fontSize: 11,
    color: colors.textMuted,
    textDecorationLine: 'underline',
  },

  // ── Dialog ──
  dialogRoot: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  scrim: { ...StyleSheet.absoluteFill, backgroundColor: 'rgba(0,0,0,0.32)' },
  dialog: {
    width: '100%',
    maxWidth: 400,
    maxHeight: '85%',
    backgroundColor: colors.paperWhite,
    borderRadius: radii.lg,
    padding: 24,
  },
  dialogTitle: { fontFamily: FONTS.serif, fontSize: 17, lineHeight: 22, fontWeight: '700', color: colors.textPrimary },
  dialogPills: { flexDirection: 'row', gap: 8, marginBottom: 10 },
  dialogDesc: { fontFamily: FONTS.sans, fontSize: 12.5, lineHeight: 17, color: colors.textSecondary },
  dialogDivider: { height: 1, backgroundColor: colors.borderRule, marginVertical: 10 },
  dialogInstructor: { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 10 },
  dialogAvatar: { width: 36, height: 36, borderRadius: 18, backgroundColor: colors.surfaceSand },
  dialogName: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.textPrimary },
  dialogRole: { fontFamily: FONTS.sans, fontSize: 11, color: colors.textMuted },
  schedule: { backgroundColor: colors.plumTint, borderRadius: radii.sm, padding: 10 },
  scheduleText: { fontFamily: FONTS.sans, fontSize: 11.5, fontWeight: '700', color: colors.plumDeep },
  scheduleLink: { fontFamily: FONTS.sans, fontSize: 11, color: colors.textSecondary, marginTop: 2 },
  dialogSeat: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 12 },
  dialogActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
    gap: 8,
    marginTop: 18,
  },
  textBtn: { height: 40, paddingHorizontal: 12, justifyContent: 'center' },
  textBtnLabel: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '500', color: colors.textSecondary },
  confirmBtn: {
    height: 40,
    borderRadius: 10,
    paddingHorizontal: 18,
    alignItems: 'center',
    justifyContent: 'center',
    minWidth: 120,
  },
  confirmLabel: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '500', color: colors.paperWhite },
});
