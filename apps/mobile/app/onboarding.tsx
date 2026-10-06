import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type TextInputProps,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { Concern, HealthGoal, OnboardingStepRequest } from '@th/types';
import { ApiRequestError } from '@/api/client';
import { useOnboardingStep, useSession, useSkipOnboarding } from '@/api/hooks';
import { TagPill } from '@/components/TagPill';
import { toIndianE164 } from '@/lib/identity';
import { CONCERNS, GOALS } from '@/lib/profileLabels';
import { FONTS, colors, radii, spacing } from '@/theme';

/**
 * Onboarding — PRD §5. Smart and minimal.
 *
 * "Every step skippable. Progress is saved as it goes, so a partial drop still
 * leaves us with usable lead data." Each step POSTs on advance rather than
 * batching at the end — that is the whole point.
 *
 * Step 4 (concern) is what makes the Home feed feel personal: it drives rail
 * ordering, and ordering only — no rail is ever hidden because of it.
 *
 * Layout and values: OnboardingScreenKt.OnboardingScreen.
 */

// GOALS and CONCERNS are exactly the design's options, in its order
// (OnboardingScreen.kt) — shared with the profile so both say the same thing.

type Step = 1 | 2 | 3 | 4;

// Copy verbatim from the design. `gap` is the subtitle's bottom padding, which
// the design varies per step (24 / 24 / 18 / 20).
const STEPS: Record<Step, { title: string; sub: string; gap: number }> = {
  1: {
    title: 'What should we call you?',
    sub: 'We use this to greet you on Home and personalize your class certificates.',
    gap: 24,
  },
  2: {
    title: 'Where can we reach you?',
    sub: 'Your daily WhatsApp class links and race bib updates will be delivered here.',
    gap: 24,
  },
  3: {
    title: 'What is your main focus?',
    sub: 'This sets the primary recommendation stack on your Home screen.',
    gap: 18,
  },
  4: {
    title: 'Any area needing special care?',
    sub: 'We will place relevant mobility and recovery sessions at the top of your feed.',
    gap: 20,
  },
};

/**
 * The server's own email check — zod's .email() in apps/api session.ts — so
 * anything accepted here is accepted there. The server refuses the WHOLE step
 * on one bad field, which would silently lose a good phone number with it.
 */
const EMAIL_RE = /^(?!\.)(?!.*\.\.)([A-Z0-9_'+\-.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9-]*\.)+[A-Z]{2,}$/i;

/** One background retry for a failed save, after this long. */
const RETRY_DELAY_MS = 3000;
/** How long a final "couldn't save" notice stays before clearing itself. */
const NOTICE_TTL_MS = 6000;

/** Worth retrying: no connection, a timeout, or the server having a bad moment. */
const isTransient = (err: unknown) =>
  err instanceof ApiRequestError && (err.status === 0 || err.status >= 500);

/** "+919876543210" → "9876543210", for a field that takes the 10 digits. */
const localNumber = (phone: string | null | undefined) =>
  toIndianE164(phone ?? '')?.slice(3) ?? phone ?? '';

export default function OnboardingScreen() {
  const router = useRouter();
  const save = useOnboardingStep();
  const skipAll = useSkipOnboarding();
  // Whatever the account already knows (the number it signed in with, a
  // Google name/email, steps saved before a drop) starts filled in, as the
  // design binds every field to the profile.
  const profile = useSession().data?.profile;

  const [step, setStep] = useState<Step>(1);
  const [name, setName] = useState(() => profile?.name ?? '');
  const [phone, setPhone] = useState(() => localNumber(profile?.phone));
  const [email, setEmail] = useState(() => profile?.email ?? '');
  const [goal, setGoal] = useState<HealthGoal | null>(() => profile?.healthGoal ?? null);
  const [concern, setConcern] = useState<Concern | null>(() => profile?.concern ?? null);

  const [phoneError, setPhoneError] = useState<string | null>(null);
  const [emailError, setEmailError] = useState<string | null>(null);
  const [finishing, setFinishing] = useState(false);
  const [notice, setNotice] = useState<{ step: Step; text: string } | null>(null);
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => clearTimeout(noticeTimer.current ?? undefined), []);

  const finish = () => router.replace('/(tabs)');
  const next = () => setStep((s) => (s < 4 ? ((s + 1) as Step) : s));

  /** Shows a notice for `owner`'s save; `ttl` clears it again. */
  const flash = (owner: Step, text: string, ttl?: number) => {
    clearTimeout(noticeTimer.current ?? undefined);
    setNotice({ step: owner, text });
    if (ttl) {
      noticeTimer.current = setTimeout(
        () => setNotice((n) => (n?.step === owner ? null : n)),
        ttl,
      );
    }
  };

  /**
   * Saves one step. A failure never blocks: the user has already moved on, a
   * small notice says so, and a transient failure is retried once in the
   * background (the mutation outlives this screen, so the step-4 retry still
   * lands after Home opens).
   */
  const persist = (payload: OnboardingStepRequest): Promise<void> =>
    save.mutateAsync(payload).then(
      () => undefined,
      (err: unknown) => {
        const owner = payload.step;
        if (!isTransient(err)) {
          // A refusal would only be refused again.
          flash(owner, 'Some details couldn’t be saved. You can add them later in your profile.', NOTICE_TTL_MS);
          return;
        }
        flash(owner, 'Couldn’t save just now — trying again in the background.');
        setTimeout(() => {
          save.mutateAsync(payload).then(
            () => setNotice((n) => (n?.step === owner ? null : n)),
            () =>
              flash(owner, 'Still couldn’t save. You can add these details later in your profile.', NOTICE_TTL_MS),
          );
        }, RETRY_DELAY_MS);
      },
    );

  const advance = () => {
    if (step === 1) {
      const n = name.trim();
      void persist({ step, ...(n ? { name: n } : {}) });
      next();
      return;
    }

    if (step === 2) {
      // Validate before posting: send only fields that are filled AND valid,
      // and hold the user here (inline) rather than lose both silently.
      const p = phone.trim();
      const e = email.trim();
      const e164 = p ? toIndianE164(p) : null;
      const pErr = p && !e164 ? 'Enter a valid 10-digit Indian mobile number.' : null;
      const eErr = e && !EMAIL_RE.test(e) ? 'Enter a valid email address, like name@example.com.' : null;
      setPhoneError(pErr);
      setEmailError(eErr);
      if (pErr || eErr) return;
      void persist({ step, ...(e164 ? { phone: e164 } : {}), ...(e ? { email: e } : {}) });
      next();
      return;
    }

    if (step === 3) {
      void persist({ step, ...(goal ? { healthGoal: goal } : {}) });
      next();
      return;
    }

    // Step 4's save is what marks onboarding complete, so wait for the first
    // attempt before opening Home — but open it whatever the outcome.
    setFinishing(true);
    void persist({ step, ...(concern ? { concern } : {}) }).finally(finish);
  };

  const skipStep = () => {
    if (step === 2) {
      // Skip leaves it blank: nothing is posted, so nothing is wrong.
      setPhoneError(null);
      setEmailError(null);
    }
    if (step === 4) {
      skipAll.mutate(undefined, { onSettled: finish });
    } else {
      next();
    }
  };

  const busy = finishing || skipAll.isPending;
  const copy = STEPS[step];

  return (
    <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.flex}
      >
        <View style={styles.frame}>
          <View style={styles.progressRow}>
            {[1, 2, 3, 4].map((i) => (
              <View
                key={i}
                style={[
                  styles.progressBar,
                  { backgroundColor: i <= step ? colors.plumBrand : colors.borderRule },
                ]}
              />
            ))}
          </View>

          <ScrollView
            // A new step starts at the top.
            key={step}
            style={styles.flex}
            contentContainerStyle={styles.scroll}
            keyboardShouldPersistTaps="handled"
            showsVerticalScrollIndicator={false}
          >
            <TagPill label={`Step ${step} of 4`} tone="CORAL" />
            <Text style={styles.title}>{copy.title}</Text>
            <Text style={[styles.sub, { marginBottom: copy.gap }]}>{copy.sub}</Text>

            {step === 1 ? (
              <OutlinedField
                label="Your full name"
                value={name}
                onChangeText={setName}
                autoCapitalize="words"
                autoComplete="name"
                maxLength={80}
                autoFocus
              />
            ) : null}

            {step === 2 ? (
              // Phone first: it is the WhatsApp channel the subtitle promises.
              <View style={styles.fieldStack}>
                <OutlinedField
                  label="Mobile number (for WhatsApp links)"
                  value={phone}
                  onChangeText={(t) => {
                    setPhone(t.replace(/[^\d+\s-]/g, ''));
                    setPhoneError(null);
                  }}
                  keyboardType="phone-pad"
                  autoComplete="tel"
                  maxLength={16}
                  error={phoneError}
                />
                <OutlinedField
                  label="Email address"
                  value={email}
                  onChangeText={(t) => {
                    setEmail(t);
                    setEmailError(null);
                  }}
                  keyboardType="email-address"
                  autoCapitalize="none"
                  autoComplete="email"
                  maxLength={160}
                  error={emailError}
                />
              </View>
            ) : null}

            {step === 3 ? (
              <View style={styles.goalList}>
                {GOALS.map((g) => {
                  const on = goal === g.value;
                  return (
                    <Pressable
                      key={g.value}
                      style={[styles.goal, on ? styles.goalOn : styles.goalOff]}
                      onPress={() => setGoal(g.value)}
                      accessibilityRole="radio"
                      accessibilityState={{ checked: on }}
                      accessibilityLabel={g.label}
                    >
                      <Text style={styles.goalEmoji}>{g.emoji}</Text>
                      <Text style={[styles.goalLabel, on && styles.goalLabelOn]}>{g.label}</Text>
                    </Pressable>
                  );
                })}
              </View>
            ) : null}

            {step === 4 ? (
              <View style={styles.concernGrid}>
                {pairs(CONCERNS).map((row) => (
                  <View key={row[0]!.value} style={styles.concernRow}>
                    {row.map((c) => {
                      const on = concern === c.value;
                      return (
                        <Pressable
                          key={c.value}
                          style={[styles.concern, on ? styles.concernOn : styles.concernOff]}
                          onPress={() => setConcern(c.value)}
                          accessibilityRole="radio"
                          accessibilityState={{ checked: on }}
                        >
                          <Text style={[styles.concernLabel, on && { color: colors.paperWhite }]}>
                            {c.label}
                          </Text>
                        </Pressable>
                      );
                    })}
                  </View>
                ))}
              </View>
            ) : null}
          </ScrollView>

          {notice ? (
            <View style={styles.notice} accessibilityLiveRegion="polite">
              <Ionicons name="cloud-offline-outline" size={14} color={colors.textSecondary} />
              <Text style={styles.noticeText}>{notice.text}</Text>
            </View>
          ) : null}

          <View style={styles.footer}>
            <Pressable
              style={styles.primaryBtn}
              onPress={advance}
              disabled={busy}
              accessibilityRole="button"
              accessibilityState={{ busy: finishing }}
            >
              {finishing ? (
                <ActivityIndicator color={colors.paperWhite} />
              ) : (
                <Text style={styles.primaryLabel}>
                  {step === 4 ? 'Finish & Open Home' : 'Continue'}
                </Text>
              )}
            </Pressable>
            {/* Every step is skippable, and skipping everything still lands on
                Home with a generic feed. Never blocked. */}
            <Pressable
              onPress={skipStep}
              style={styles.skip}
              disabled={busy}
              accessibilityRole="button"
            >
              <Text style={styles.skipLabel}>{step === 4 ? 'Skip for now' : 'Skip this step'}</Text>
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

/** The design lays concerns out with chunked(2): a two-column grid. */
function pairs<T>(items: T[]): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += 2) out.push(items.slice(i, i + 2));
  return out;
}

/**
 * The design's OutlinedTextField: 56dp, 14dp corners, borderRule outline that
 * turns plumBrand (2dp) on focus. Label and cursor keep M3's primary (coral) —
 * the design overrides only the border colours. The label sits inside until
 * focused or filled, then floats onto the border. Errors are ours (inline
 * validation), drawn in M3's error style.
 */
function OutlinedField({
  label,
  error,
  value,
  onFocus,
  onBlur,
  style,
  ...input
}: TextInputProps & { label: string; error?: string | null }) {
  const [focused, setFocused] = useState(false);
  const floated = focused || !!value;
  const lift = useRef(new Animated.Value(floated ? 1 : 0)).current;

  useEffect(() => {
    Animated.timing(lift, { toValue: floated ? 1 : 0, duration: 150, useNativeDriver: false }).start();
  }, [floated, lift]);

  const bw = focused ? 2 : 1;
  const border = error ? colors.crimsonAlert : focused ? colors.plumBrand : colors.borderRule;
  const accent = error ? colors.crimsonAlert : focused ? colors.coralBrand : colors.textSecondary;

  return (
    <View>
      <View
        style={[
          styles.field,
          // RN borders take layout space (Compose's don't): keep the text at 16dp.
          { borderWidth: bw, borderColor: border, paddingHorizontal: 16 - bw },
        ]}
      >
        <TextInput
          value={value}
          onFocus={(e) => {
            setFocused(true);
            onFocus?.(e);
          }}
          onBlur={(e) => {
            setFocused(false);
            onBlur?.(e);
          }}
          selectionColor={colors.coralBrand}
          cursorColor={colors.coralBrand}
          accessibilityLabel={label}
          style={[styles.fieldInput, style]}
          {...input}
        />
        <Animated.Text
          pointerEvents="none"
          numberOfLines={1}
          style={[
            styles.fieldLabel,
            {
              left: 12 - bw,
              color: accent,
              backgroundColor: floated ? colors.canvasBg : 'transparent',
              top: lift.interpolate({ inputRange: [0, 1], outputRange: [18 - bw, -8 - bw / 2] }),
              fontSize: lift.interpolate({ inputRange: [0, 1], outputRange: [16, 12] }),
              lineHeight: lift.interpolate({ inputRange: [0, 1], outputRange: [20, 16] }),
            },
          ]}
        >
          {label}
        </Animated.Text>
      </View>
      {error ? (
        <Text style={styles.fieldError} accessibilityLiveRegion="polite">
          {error}
        </Text>
      ) : null}
    </View>
  );
}

// Compose draws a border INSIDE the padded box; RN lays it out. Paddings below
// subtract the border width so content sits where the design puts it.
const GOAL_BORDER = 1.2;
const CONCERN_BORDER = 1;

// Un-styled Compose Text inherits M3 bodyLarge's 22sp line height; that is
// what the design actually renders for the 12–14sp copy below.
const INHERITED_LINE = 22;

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  flex: { flex: 1 },
  frame: { flex: 1, padding: spacing['5xl'] },
  progressRow: { flexDirection: 'row', gap: spacing.sm },
  progressBar: { flex: 1, height: 4, borderRadius: 2 },
  scroll: { paddingTop: spacing['6xl'], paddingBottom: spacing['5xl'] },
  title: {
    fontFamily: FONTS.serif,
    fontSize: 30,
    lineHeight: 34,
    fontWeight: '700',
    color: colors.textPrimary,
    marginTop: spacing.lg,
  },
  sub: {
    fontFamily: FONTS.sans,
    fontSize: 13,
    lineHeight: INHERITED_LINE,
    color: colors.textSecondary,
    marginTop: spacing.sm,
  },

  fieldStack: { gap: spacing.xl },
  field: {
    height: 56,
    borderRadius: radii.option,
    flexDirection: 'row',
    alignItems: 'center',
  },
  fieldInput: {
    flex: 1,
    alignSelf: 'stretch',
    padding: 0,
    fontFamily: FONTS.sans,
    fontSize: 16,
    color: colors.textPrimary,
  },
  fieldLabel: { position: 'absolute', paddingHorizontal: spacing.xs, fontFamily: FONTS.sans, maxWidth: '90%' },
  fieldError: {
    fontFamily: FONTS.sans,
    fontSize: 11,
    lineHeight: 15,
    color: colors.crimsonAlert,
    marginTop: spacing.xs,
    paddingHorizontal: spacing['3xl'],
  },

  goalList: { gap: spacing.md },
  goal: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xl,
    borderRadius: radii.option,
    borderWidth: GOAL_BORDER,
    padding: spacing.xxl - GOAL_BORDER,
  },
  goalOn: { backgroundColor: colors.plumTint, borderColor: colors.plumBrand },
  goalOff: { backgroundColor: colors.paperWhite, borderColor: colors.borderRule },
  goalEmoji: { fontSize: 20, lineHeight: 26 },
  goalLabel: {
    flex: 1,
    fontFamily: FONTS.sans,
    fontSize: 14,
    lineHeight: INHERITED_LINE,
    fontWeight: '500',
    color: colors.textPrimary,
  },
  goalLabelOn: { fontWeight: '700', color: colors.plumDeep },

  concernGrid: { gap: spacing.md },
  concernRow: { flexDirection: 'row', gap: spacing.md },
  concern: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radii.option,
    borderWidth: CONCERN_BORDER,
    paddingHorizontal: spacing.lg - CONCERN_BORDER,
    paddingVertical: spacing.xxl - CONCERN_BORDER,
  },
  concernOn: { backgroundColor: colors.plumBrand, borderColor: colors.plumBrand },
  concernOff: { backgroundColor: colors.paperWhite, borderColor: colors.borderRule },
  concernLabel: {
    fontFamily: FONTS.sans,
    fontSize: 12.5,
    lineHeight: INHERITED_LINE,
    fontWeight: '700',
    color: colors.textPrimary,
    textAlign: 'center',
  },

  notice: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: colors.surfaceSand,
    borderRadius: radii.sm,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
    marginBottom: spacing.lg,
  },
  noticeText: { flex: 1, fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: 16, color: colors.textSecondary },

  footer: { alignItems: 'center' },
  primaryBtn: {
    alignSelf: 'stretch',
    height: 52,
    borderRadius: radii.option,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  // M3 Button text is labelLarge (+0.2 tracking); the design sets 14sp bold.
  primaryLabel: {
    fontFamily: FONTS.sans,
    fontSize: 14,
    lineHeight: 18,
    fontWeight: '700',
    letterSpacing: 0.2,
    color: colors.paperWhite,
  },
  // M3 TextButton: 40dp minimum, 12×8 padding; 4dp above it in the design.
  skip: {
    marginTop: spacing.xs,
    minHeight: 40,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
    justifyContent: 'center',
  },
  skipLabel: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 16,
    fontWeight: '600',
    letterSpacing: 0.2,
    color: colors.textMuted,
  },
});
