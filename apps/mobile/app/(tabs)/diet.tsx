import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  Easing,
  KeyboardAvoidingView,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type KeyboardTypeOptions,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { UserQuote } from '@th/types';
import { ApiRequestError } from '@/api/client';
import { useSession, useSubmitDietLead } from '@/api/hooks';
import { CardImage, QuoteCard } from '@/components/feed/Cards';
import { TagPill } from '@/components/TagPill';
import { toIndianE164 } from '@/lib/identity';
import { FONTS, colors, layout, radii } from '@/theme';

/**
 * DIET TAB — PRD §9. Identical for every user, no entitlement logic.
 * Value prop → CTA → lead form → confirmation ("our dietitian will call you").
 *
 * Layout and copy are DietScreenKt's: a full-bleed plum hero with the in-hero
 * proof row, the Indian Home Kitchen card, "Why it sticks", the conditions
 * grid, member quotes and a closing plum CTA. Both CTAs open DietLeadSheet (a
 * bottom sheet); success is DietSuccessDialog (a centred dialog).
 *
 * No plans, no prices, no payment in V1. Known gap: an existing diet customer
 * sees this pitch too, because diet purchases cannot be mapped to app accounts.
 *
 * The brand bar above this tab is the shared TopHeader (tabs layout), which
 * also owns the status-bar inset — so this screen draws no header of its own.
 */

const HERO_IMAGE =
  'https://images.unsplash.com/photo-1490645935967-10de6ba17061?auto=format&fit=crop&w=800&q=80';
const KITCHEN_IMAGE =
  'https://images.unsplash.com/photo-1512621776951-a57141f2eefd?auto=format&fit=crop&w=800&q=80';

/** Hero proof row (DietScreenKt:1152-1242). */
const HERO_STATS = [
  { value: 'Daily', label: 'WhatsApp support' },
  { value: 'Weekly', label: '1-on-1 consults' },
  { value: '8 Cuisines', label: 'Regional Indian meals' },
];

/** "Why it sticks" — ComposableSingletons lambda 1293425603, verbatim. */
const WHY = [
  {
    title: '2,500+ Indian meal combinations',
    body: 'From Punjabi dal to South Indian rasam — food you actually eat and cook every day.',
  },
  {
    title: 'Qualified certified clinical dietitians',
    body: "Master's degree nutritionists who build and modify your plan as your life shifts.",
  },
  {
    title: 'No crash diets or deprivation',
    body: 'Built to work with social dinners, travel, and family routines without guilt.',
  },
  {
    title: '16+ medical conditions supported',
    body: 'Evidence-based protocols for PCOS, Type 2 Diabetes, Thyroid, and Cholesterol.',
  },
];

/** TimesHealthRepository.dietConditions, shown chunked(3). */
const CONDITIONS = [
  'Weight loss',
  'Lower back / Joint pain',
  'PCOS / PCOD',
  'Type 2 Diabetes',
  'Thyroid imbalance',
  'Acid reflux / GERD',
  'Hypertension',
  'Fatty liver',
  'Marathon race fuel',
];

/** DietLeadSheet: the first six of its list (take(6)), single-select. */
const CONCERNS = [
  'Weight loss',
  'PCOS / PCOD',
  'Type 2 diabetes',
  'Pre-diabetes',
  'Thyroid health',
  'Iron deficiency',
];

const CALL_TIMES = ['Morning', 'Afternoon', 'Evening'];

/**
 * "Real member results" — the design prototype's own placeholders, exactly as
 * attributed there (DietScreen.kt). Before launch these must be replaced with
 * real testimonials the members have consented to, and the health outcomes
 * ("HbA1c dropped", "Lost 9 kg") verified — app-store reviewers scrutinise
 * medical claims.
 */
const RESULTS: UserQuote[] = [
  {
    id: 'diet-sonia',
    quote: 'Other plans stopped working after two weeks. This one actually fit my kitchen.',
    author: 'Sonia Goyal',
    role: 'Lost 9 kg · Mumbai',
  },
  {
    id: 'diet-rajeshwari',
    quote: 'Proper portions, normal meals. My HbA1c dropped in two months.',
    author: 'Rajeshwari M.',
    role: 'Type 2 Diabetes',
  },
  {
    id: 'diet-vikram',
    quote: 'My dietitian calls on time every week. That accountability made all the difference.',
    author: 'Vikram S.',
    role: 'Lost 7 kg · Bengaluru',
  },
];

const DONE_MESSAGE =
  'A TimesHealth+ certified dietitian will call your registered number within one working day to conduct your 15-minute consultation.';

type Sheet = 'closed' | 'form' | { title: string; message: string };

export default function DietTab() {
  const [sheet, setSheet] = useState<Sheet>('closed');
  const openForm = () => setSheet('form');

  return (
    <View style={styles.root}>
      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <Hero onBook={openForm} />

        <View style={{ height: 14 }} />
        <KitchenCard />

        <View style={{ height: 16 }} />
        <SectionHeader title="Why it sticks" />
        {WHY.map((w) => (
          <View key={w.title} style={styles.whyCard}>
            <Text style={styles.whyTitle}>{w.title}</Text>
            <Text style={styles.whyBody}>{w.body}</Text>
          </View>
        ))}

        <View style={{ height: 16 }} />
        <SectionHeader title="Conditions we help manage" />
        <ConditionGrid />

        <View style={{ height: 18 }} />
        <SectionHeader title="Real member results" />
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.quoteRail}
        >
          {RESULTS.map((q) => (
            <QuoteCard key={q.id} quote={q} />
          ))}
        </ScrollView>

        <View style={{ height: 18 }} />
        <View style={styles.ctaCard}>
          <Text style={styles.ctaTitle}>Start with a Free 15-Min Call</Text>
          <Text style={styles.ctaBody}>
            Talk to a certified clinical dietitian. No commitment, no payment required in V1.
          </Text>
          <Pressable
            style={({ pressed }) => [styles.ctaBtn, pressed && styles.pressed]}
            onPress={openForm}
            accessibilityRole="button"
          >
            <Text style={styles.ctaBtnText}>Book My Free Call</Text>
          </Pressable>
        </View>
      </ScrollView>

      <LeadModal
        sheet={sheet}
        onClose={() => setSheet('closed')}
        onDone={(title, message) => setSheet({ title, message })}
      />
    </View>
  );
}

/** DietScreenKt:1002-1080 — full bleed, bottom corners 26, padding 20×24. */
function Hero({ onBook }: { onBook: () => void }) {
  return (
    <View style={styles.hero}>
      <CardImage
        uri={HERO_IMAGE}
        fallback={{ colors: [colors.plumBrand, colors.plumDeep] }}
        scrim={{ colors: ['rgba(0,0,0,0.55)', 'rgba(0,0,0,0.9)'], dir: 'vertical' }}
      />
      <TagPill label="Dietitian-led nutrition" tone="GOLD" />
      <View style={{ height: 10 }} />
      <Text style={styles.heroTitle}>{'Your goal. Your food.\nYour dietitian.'}</Text>
      <Text style={styles.heroBody}>
        A personalized nutrition plan built around what you already cook at home, with a dedicated
        dietitian checking in weekly.
      </Text>
      <Pressable
        style={({ pressed }) => [styles.heroBtn, pressed && styles.pressed]}
        onPress={onBook}
        accessibilityRole="button"
      >
        <Text style={styles.heroBtnText}>Book a Free Consult</Text>
      </Pressable>
      <View style={{ height: 16 }} />
      <View style={styles.heroStats}>
        {HERO_STATS.map((s) => (
          <View key={s.label}>
            <Text style={styles.heroStatValue}>{s.value}</Text>
            <Text style={styles.heroStatLabel}>{s.label}</Text>
          </View>
        ))}
      </View>
    </View>
  );
}

/** ComposableSingletons lambda 1124281828 — R18 outlined card, 130dp image. */
function KitchenCard() {
  return (
    <View style={styles.kitchen}>
      <View style={styles.kitchenImage}>
        <CardImage
          uri={KITCHEN_IMAGE}
          fallback={{ colors: [colors.sageBrand, colors.sageSecondary], dir: 'horizontal' }}
          scrim={{ colors: ['transparent', 'rgba(0,0,0,0.7)'], dir: 'vertical' }}
        />
        <TagPill label="Indian home kitchen" tone="EMERALD" style={styles.kitchenPill} />
        {/* Design copy minus its double "over … +". */}
        <Text style={styles.kitchenTitle}>2,500+ Macro-Balanced Regional Recipes</Text>
      </View>
      <View style={{ padding: 14 }}>
        <Text style={styles.kitchenBody}>
          Eat what your family eats. Your dedicated dietitian designs portion macros around your
          favorite home-cooked dishes, lentils, and grains.
        </Text>
      </View>
    </View>
  );
}

/** Lambda 1462569378: chunked(3) rows of weight(1) pills, spacedBy 6. */
function ConditionGrid() {
  const rows: string[][] = [];
  for (let i = 0; i < CONDITIONS.length; i += 3) rows.push(CONDITIONS.slice(i, i + 3));
  return (
    <View style={styles.grid}>
      {rows.map((row) => (
        <View key={row.join()} style={styles.gridRow}>
          {row.map((c) => (
            <View key={c} style={styles.conditionPill}>
              <Text style={styles.conditionText} numberOfLines={1}>
                {c}
              </Text>
            </View>
          ))}
          {/* Keep a short last row on the same 3-column grid. */}
          {Array.from({ length: 3 - row.length }, (_, i) => (
            <View key={`pad-${i}`} style={styles.gridPad} />
          ))}
        </View>
      ))}
    </View>
  );
}

function SectionHeader({ title }: { title: string }) {
  return (
    <Text style={styles.sectionTitle} accessibilityRole="header">
      {title}
    </Text>
  );
}

/** Material bottom-sheet motion, as NotificationInbox: scrim fades, sheet rises. */
function useRise(active: boolean) {
  const rise = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (!active) return;
    rise.setValue(0);
    Animated.timing(rise, {
      toValue: 1,
      duration: 260,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [active, rise]);
  return rise.interpolate({ inputRange: [0, 1], outputRange: [560, 0] });
}

/**
 * DietLeadSheet + DietSuccessDialog in ONE modal: the sheet becomes the dialog
 * in place. Presenting a second modal while the first is still dismissing is
 * dropped on iOS, which would lose the confirmation.
 */
function LeadModal({
  sheet,
  onClose,
  onDone,
}: {
  sheet: Sheet;
  onClose: () => void;
  onDone: (title: string, message: string) => void;
}) {
  const insets = useSafeAreaInsets();
  const { data: session } = useSession();
  const submit = useSubmitDietLead();
  const translateY = useRise(sheet === 'form');

  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [concern, setConcern] = useState(CONCERNS[0]!);
  const [callTime, setCallTime] = useState(CALL_TIMES[0]!);
  const [nameError, setNameError] = useState<string | null>(null);
  const [phoneError, setPhoneError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  // Prefill from the profile (which falls back to the contact details typed
  // at onboarding), without overwriting anything the user has typed.
  const profileName = session?.profile.name ?? '';
  const profilePhone = session?.profile.phone ?? '';
  useEffect(() => {
    if (profileName) setName((n) => n || profileName);
    if (profilePhone) setPhone((p) => p || (toIndianE164(profilePhone)?.slice(3) ?? profilePhone));
  }, [profileName, profilePhone]);

  // A stale "couldn't submit" from an earlier attempt shouldn't greet a reopen.
  useEffect(() => {
    if (sheet === 'form') setFormError(null);
  }, [sheet]);

  const onSubmit = () => {
    setNameError(null);
    setPhoneError(null);
    setFormError(null);
    const trimmed = name.trim();
    // A dietitian has to be able to call this number — check it here so the
    // most common mistake gets an instant answer instead of a round trip.
    const e164 = toIndianE164(phone);
    if (!trimmed) setNameError('Enter your name so the dietitian knows who to ask for.');
    if (!e164) setPhoneError('Enter a valid 10-digit Indian mobile number.');
    if (!trimmed || !e164) return;

    submit.mutate(
      { name: trimmed, phone: e164, condition: concern, cuisinePreference: null, bestTimeToCall: callTime },
      {
        onSuccess: (res) => onDone('You’re on the list!', res.message || DONE_MESSAGE),
        onError: (e) => {
          if (e instanceof ApiRequestError) {
            // The server's own check (normalizePhone) has the last word.
            if (e.body.code === 'INVALID_PHONE') return setPhoneError(e.message);
            // Three requests a day is the cap: they are already in the queue,
            // which is good news — confirm it rather than show an error.
            if (e.status === 429) return onDone('You’re already on the list', e.message);
            if (e.status === 0) return setFormError(e.message);
          }
          setFormError('We couldn’t submit that. Check your connection and try again.');
        },
      },
    );
  };

  const done = typeof sheet === 'object' ? sheet : null;

  return (
    <Modal
      visible={sheet !== 'closed'}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onClose}
    >
      {/* 'padding' on both platforms: the modal draws edge to edge, so Android
          does not resize it for the keyboard — the padding lifts the sheet,
          and settles back to zero wherever the window does resize. */}
      <KeyboardAvoidingView behavior="padding" style={styles.modalRoot}>
        <Pressable
          style={styles.scrim}
          onPress={onClose}
          accessibilityRole="button"
          accessibilityLabel="Close"
        />

        {done ? (
          <View style={styles.dialogWrap} pointerEvents="box-none">
            <View style={styles.dialog} accessibilityViewIsModal>
              <Text style={styles.dialogTitle}>{done.title}</Text>
              <Text style={styles.dialogBody}>{done.message}</Text>
              <Pressable
                style={({ pressed }) => [styles.dialogBtn, pressed && styles.pressed]}
                onPress={onClose}
                accessibilityRole="button"
              >
                <Text style={styles.dialogBtnText}>Done</Text>
              </Pressable>
            </View>
          </View>
        ) : (
          <Animated.View
            style={[styles.sheet, { paddingBottom: insets.bottom + 8, transform: [{ translateY }] }]}
          >
            <View style={styles.handle} />
            <ScrollView
              style={{ flexGrow: 0 }}
              contentContainerStyle={styles.sheetBody}
              keyboardShouldPersistTaps="handled"
              showsVerticalScrollIndicator={false}
            >
              <View style={styles.sheetHeader}>
                <Text style={styles.sheetTitle}>Book Your Free 15-Min Consult</Text>
                <Pressable
                  onPress={onClose}
                  style={styles.sheetClose}
                  accessibilityRole="button"
                  accessibilityLabel="Close"
                >
                  <Text style={styles.sheetCloseText}>✕</Text>
                </Pressable>
              </View>
              <Text style={styles.sheetSub}>
                A qualified clinical dietitian will call you within one working day.
              </Text>

              <View style={{ height: 14 }} />
              <OutlinedField
                label="Your Full Name"
                value={name}
                onChangeText={(v) => {
                  setName(v);
                  setNameError(null);
                }}
                error={nameError}
                autoComplete="name"
              />
              <View style={{ height: 10 }} />
              <OutlinedField
                label="Mobile Number (Call & WhatsApp)"
                value={phone}
                onChangeText={(v) => {
                  setPhone(v);
                  setPhoneError(null);
                }}
                keyboardType="phone-pad"
                maxLength={16}
                error={phoneError}
                autoComplete="tel"
              />

              <View style={{ height: 12 }} />
              <Text style={styles.fieldHeading}>What would you like help with?</Text>
              <View style={{ height: 4 }} />
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={{ gap: 6 }}
                keyboardShouldPersistTaps="handled"
              >
                {CONCERNS.map((c) => (
                  <FilterChip key={c} label={c} selected={concern === c} onPress={() => setConcern(c)} />
                ))}
              </ScrollView>

              <View style={{ height: 10 }} />
              <Text style={styles.fieldHeading}>Preferred Call Time</Text>
              <View style={{ height: 4 }} />
              <View style={{ flexDirection: 'row', gap: 8 }}>
                {CALL_TIMES.map((t) => (
                  <FilterChip key={t} label={t} selected={callTime === t} onPress={() => setCallTime(t)} />
                ))}
              </View>

              <View style={{ height: 18 }} />
              <Pressable
                style={({ pressed }) => [styles.submitBtn, pressed && styles.pressed]}
                disabled={submit.isPending}
                onPress={onSubmit}
                accessibilityRole="button"
                accessibilityState={{ busy: submit.isPending }}
              >
                {submit.isPending ? (
                  <ActivityIndicator color={colors.paperWhite} />
                ) : (
                  <Text style={styles.submitText}>Request Free Dietitian Call</Text>
                )}
              </Pressable>
              {formError ? <Text style={styles.formError}>{formError}</Text> : null}
              <View style={{ height: 12 }} />
            </ScrollView>
          </Animated.View>
        )}
      </KeyboardAvoidingView>
    </Modal>
  );
}

/**
 * The design's M3 OutlinedTextField: 12dp corners, no fill, a label that sits
 * in the field and floats onto the outline once there is focus or text.
 */
function OutlinedField({
  label,
  value,
  onChangeText,
  keyboardType,
  maxLength,
  error,
  autoComplete,
}: {
  label: string;
  value: string;
  onChangeText: (v: string) => void;
  keyboardType?: KeyboardTypeOptions;
  maxLength?: number;
  error: string | null;
  autoComplete?: 'name' | 'tel';
}) {
  const [focused, setFocused] = useState(false);
  const floated = focused || value.length > 0;
  const tint = error ? colors.crimsonAlert : focused ? colors.plumBrand : colors.textMuted;
  return (
    <View>
      <View
        style={[
          styles.field,
          focused && { borderColor: colors.plumBrand, borderWidth: 2 },
          error ? { borderColor: colors.crimsonAlert } : null,
        ]}
      >
        {floated ? <Text style={[styles.fieldLabel, { color: tint }]}>{label}</Text> : null}
        <TextInput
          value={value}
          onChangeText={onChangeText}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          placeholder={floated ? undefined : label}
          placeholderTextColor={colors.textMuted}
          keyboardType={keyboardType}
          maxLength={maxLength}
          autoComplete={autoComplete}
          accessibilityLabel={label}
          style={styles.fieldInput}
        />
      </View>
      {error ? <Text style={styles.fieldError}>{error}</Text> : null}
    </View>
  );
}

/** M3 FilterChip: 32dp, R8; selected secondaryContainer (sageTint), 11sp label. */
function FilterChip({
  label,
  selected,
  onPress,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={[styles.chip, selected && styles.chipOn]}
      accessibilityRole="button"
      accessibilityState={{ selected }}
    >
      <Text style={[styles.chipText, selected && styles.chipTextOn]}>{label}</Text>
    </Pressable>
  );
}

const GUTTER = layout.screenGutter;

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  // LazyColumn contentPadding(bottom = 90).
  scroll: { paddingBottom: 90 },
  pressed: { opacity: 0.85 },

  hero: {
    overflow: 'hidden',
    borderBottomLeftRadius: 26,
    borderBottomRightRadius: 26,
    paddingHorizontal: 20,
    paddingVertical: 24,
  },
  heroTitle: {
    fontFamily: FONTS.serif,
    fontSize: 32,
    lineHeight: 36,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  heroBody: {
    fontFamily: FONTS.sans,
    fontSize: 13,
    lineHeight: 18,
    color: 'rgba(255,255,255,0.85)',
    marginTop: 8,
    marginBottom: 18,
  },
  heroBtn: {
    height: 48,
    borderRadius: radii.md,
    backgroundColor: colors.paperWhite,
    alignItems: 'center',
    justifyContent: 'center',
  },
  heroBtnText: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.plumDeep },
  heroStats: { flexDirection: 'row', justifyContent: 'space-between' },
  heroStatValue: { fontFamily: FONTS.serif, fontSize: 17, fontWeight: '700', color: colors.paperWhite },
  heroStatLabel: { fontFamily: FONTS.sans, fontSize: 10.5, color: 'rgba(255,255,255,0.7)' },

  kitchen: {
    marginHorizontal: GUTTER,
    borderRadius: 18,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    overflow: 'hidden',
  },
  kitchenImage: { height: 130 },
  kitchenPill: { position: 'absolute', top: 12, left: 12 },
  kitchenTitle: {
    position: 'absolute',
    left: 12,
    right: 12,
    bottom: 12,
    fontFamily: FONTS.serif,
    fontSize: 15,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  kitchenBody: { fontFamily: FONTS.sans, fontSize: 12, lineHeight: 17, color: colors.textSecondary },

  // CommonComponentsKt.SectionHeader: serif 18 SemiBold, padding(18, 12).
  sectionTitle: {
    fontFamily: FONTS.serif,
    fontSize: 18,
    lineHeight: 22,
    fontWeight: '600',
    color: colors.textPrimary,
    paddingHorizontal: GUTTER,
    paddingVertical: 12,
  },

  whyCard: {
    marginHorizontal: GUTTER,
    marginVertical: 4,
    borderRadius: radii.option,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderRule,
    padding: 14,
  },
  whyTitle: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.textPrimary },
  whyBody: {
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    lineHeight: 16,
    color: colors.textSecondary,
    marginTop: 2,
  },

  grid: { paddingHorizontal: GUTTER, gap: 6 },
  gridRow: { flexDirection: 'row', gap: 6 },
  gridPad: { flex: 1 },
  conditionPill: {
    flex: 1,
    borderRadius: 20,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderRule,
    paddingHorizontal: 4,
    paddingVertical: 7,
    alignItems: 'center',
  },
  conditionText: { fontFamily: FONTS.sans, fontSize: 10, fontWeight: '700', color: colors.textPrimary },

  quoteRail: { paddingHorizontal: GUTTER, gap: 12 },

  // Closing CTA (DietScreenKt:1614-1691): R18, plumTint, plumLine, padding 18.
  ctaCard: {
    marginHorizontal: GUTTER,
    borderRadius: 18,
    backgroundColor: colors.plumTint,
    borderWidth: 1,
    borderColor: colors.plumLine,
    padding: 18,
  },
  ctaTitle: { fontFamily: FONTS.serif, fontSize: 19, fontWeight: '700', color: colors.plumDeep },
  ctaBody: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
    marginTop: 4,
    marginBottom: 14,
  },
  ctaBtn: {
    height: 48,
    borderRadius: radii.md,
    backgroundColor: colors.plumBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  ctaBtnText: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '700', color: colors.paperWhite },

  // ── Lead sheet ──
  modalRoot: { flex: 1, justifyContent: 'flex-end' },
  // M3 ModalBottomSheet scrim: 32% black.
  scrim: { ...StyleSheet.absoluteFill, backgroundColor: 'rgba(0,0,0,0.32)' },
  sheet: {
    maxHeight: '92%',
    backgroundColor: colors.paperWhite,
    borderTopLeftRadius: radii.sheet,
    borderTopRightRadius: radii.sheet,
  },
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: 2,
    marginVertical: 8,
    backgroundColor: colors.borderRule,
  },
  // DietLeadSheet: padding(22, 8).
  sheetBody: { paddingHorizontal: 22, paddingVertical: 8 },
  sheetHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  sheetTitle: {
    flex: 1,
    fontFamily: FONTS.serif,
    fontSize: 20,
    lineHeight: 25,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  sheetClose: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center', marginRight: -12 },
  sheetCloseText: { fontSize: 16, color: colors.textMuted },
  sheetSub: { fontFamily: FONTS.sans, fontSize: 12, lineHeight: 16, color: colors.textSecondary },

  field: {
    height: 56,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    justifyContent: 'center',
    paddingHorizontal: 16,
  },
  fieldLabel: {
    position: 'absolute',
    top: -9,
    left: 12,
    paddingHorizontal: 4,
    backgroundColor: colors.paperWhite,
    fontFamily: FONTS.sans,
    fontSize: 12,
  },
  fieldInput: {
    fontFamily: FONTS.sans,
    fontSize: 15,
    color: colors.textPrimary,
    paddingVertical: 0,
  },
  fieldError: {
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    lineHeight: 15,
    color: colors.crimsonAlert,
    marginTop: 4,
    marginLeft: 16,
  },
  fieldHeading: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.textPrimary },

  chip: {
    height: 32,
    borderRadius: radii.sm,
    borderWidth: 1,
    borderColor: colors.borderRule,
    paddingHorizontal: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  chipOn: { backgroundColor: colors.sageTint, borderColor: colors.sageTint },
  chipText: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '500', color: colors.textSecondary },
  chipTextOn: { color: colors.sageBrand, fontWeight: '700' },

  // Button h50, R12, PlumBrand — always enabled; validation answers on tap.
  submitBtn: {
    height: 50,
    borderRadius: radii.md,
    backgroundColor: colors.plumBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  submitText: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '700', color: colors.paperWhite },
  formError: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 16,
    color: colors.crimsonAlert,
    textAlign: 'center',
    marginTop: 10,
  },

  // ── Success dialog (M3 AlertDialog: R28, padding 24) ──
  dialogWrap: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center', padding: 24 },
  dialog: {
    width: '100%',
    maxWidth: 360,
    backgroundColor: colors.paperWhite,
    borderRadius: radii.sheet,
    padding: 24,
  },
  dialogTitle: {
    fontFamily: FONTS.serif,
    fontSize: 24,
    lineHeight: 30,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 16,
  },
  dialogBody: { fontFamily: FONTS.sans, fontSize: 13, lineHeight: 18, color: colors.textSecondary },
  dialogBtn: {
    alignSelf: 'flex-end',
    height: 40,
    borderRadius: radii.pill,
    backgroundColor: colors.carbon950,
    paddingHorizontal: 24,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 24,
  },
  dialogBtnText: { fontFamily: FONTS.sans, fontSize: 14, fontWeight: '500', color: colors.paperWhite },
});
