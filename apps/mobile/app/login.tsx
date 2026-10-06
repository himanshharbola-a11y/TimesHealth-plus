import { useCallback, useEffect, useRef, useState, type Ref } from 'react';
import {
  ActivityIndicator,
  Animated,
  BackHandler,
  Keyboard,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
  type StyleProp,
  type TextInputProps,
  type ViewStyle,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  AuthError,
  type PhoneSession,
  identityConfigured,
  googleEnabled,
  sendPasswordReset,
  signInWithEmail,
  signInWithGoogle,
  startPhoneSignIn,
  toIndianE164,
} from '@/lib/identity';
import { DEV_PERSONAS, devSignInEnabled, useSessionStore } from '@/store/session';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, radii, spacing, type, upper } from '@/theme';

/**
 * Pre-login + login — PRD §5: "Pre-login screen — auto-moving carousel,
 * single CTA → Login — Google / Email / Phone". Login is mandatory; there is
 * no guest mode.
 *
 * Two screens from the design, one route:
 *   1. PreLoginSplash — OnboardingScreenKt.PreLoginSplashScreen: full-bleed
 *      gradient per slide, wordmark, white "Get Started" card.
 *   2. AuthScreen — LoginScreenKt.LoginScreen: #1C0722 backdrop, the slide's
 *      highlight stays visible above a canvas-coloured card (28dp top corners)
 *      holding the actual sign-in.
 */

/** PRD §5's carousel moves on its own; ~4s a slide. */
const AUTO_ADVANCE_MS = 4000;

/** LoginScreenKt's backdrop, Color(0xFF1C0722) — not a theme token. */
const AUTH_BG = '#1C0722';

// PreLoginSplashScreen's three slides, verbatim — copy and the vertical
// gradient pairs (Color(0xFF3B0E4A) → Color(0xFF150A1B), …). No photography.
const SPLASH_SLIDES = [
  {
    title: 'Eight live classes a day.',
    body: 'Certified master instructors from The Yoga Institute, on your mat, morning and evening.',
    gradient: ['#3B0E4A', '#150A1B'] as const,
  },
  {
    title: 'Run the city with us.',
    body: 'Half marathons across Hyderabad, Bengaluru, Mumbai and Delhi NCR with chip timing.',
    gradient: ['#1E284A', '#11172E'] as const,
  },
  {
    title: 'A dietitian who knows your food.',
    body: 'Personalized nutrition built on what you already cook at home. 2,500+ Indian meal combos.',
    gradient: ['#2E1C07', '#181005'] as const,
  },
];

// LoginScreenKt's highlights (its listOf(Triple(...))), verbatim. Same three
// topics in the same order as the splash, so the slide carries across.
const HIGHLIGHTS = [
  {
    title: 'Eight live classes a day.',
    body: 'Certified instructors from The Yoga Institute on your mat at your hour.',
    emoji: '🧘',
  },
  {
    title: 'Run the city with us.',
    body: 'Half marathons across Hyderabad, Delhi NCR, Bengaluru & Mumbai.',
    emoji: '🏃',
  },
  {
    title: 'A dietitian who knows your food.',
    body: 'Plans built around Indian food you already cook. First call is free.',
    emoji: '🥗',
  },
];

export default function LoginScreen() {
  const router = useRouter();
  const status = useSessionStore((s) => s.status);
  // null = still on the pre-login splash; otherwise the slide to open on.
  const [authSlide, setAuthSlide] = useState<number | null>(null);

  // Navigate when auth settles rather than straight after a sign-in call:
  // Firebase reports the new user through its listener a moment later, and
  // navigating first would let the gate bounce us back here.
  useEffect(() => {
    if (status === 'signed-in') router.replace('/');
  }, [status, router]);

  // Android back from the sign-in card returns to the splash instead of
  // leaving the app — the splash is the only way back to it.
  useEffect(() => {
    if (authSlide === null) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      setAuthSlide(null);
      return true;
    });
    return () => sub.remove();
  }, [authSlide]);

  return (
    <>
      {/* Both screens are dark at the top. */}
      <StatusBar style="light" />
      {authSlide === null ? (
        <PreLoginSplash onContinue={setAuthSlide} />
      ) : (
        <AuthScreen initialSlide={authSlide} />
      )}
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Pre-login splash
// ─────────────────────────────────────────────────────────────────────────────

function PreLoginSplash({ onContinue }: { onContinue: (slide: number) => void }) {
  const { width } = useWindowDimensions();
  const pager = useRef<ScrollView>(null);
  const [slide, setSlide] = useState(0);
  // True while a finger is on the carousel: it must not move under the user.
  const [holding, setHolding] = useState(false);
  // One opacity per gradient so a slide change cross-fades the backdrop.
  const fades = useRef(SPLASH_SLIDES.map((_, i) => new Animated.Value(i === 0 ? 1 : 0))).current;

  const goTo = useCallback(
    (i: number) => {
      setSlide(i);
      pager.current?.scrollTo({ x: i * width, animated: true });
    },
    [width],
  );

  // Auto-moving carousel (§5). A timeout per slide rather than an interval, so
  // a swipe or an indicator tap restarts the full 4s instead of jumping early.
  useEffect(() => {
    if (holding) return;
    const t = setTimeout(() => goTo((slide + 1) % SPLASH_SLIDES.length), AUTO_ADVANCE_MS);
    return () => clearTimeout(t);
  }, [slide, holding, goTo]);

  useEffect(() => {
    Animated.parallel(
      fades.map((v, i) =>
        Animated.timing(v, { toValue: i === slide ? 1 : 0, duration: 450, useNativeDriver: true }),
      ),
    ).start();
  }, [slide, fades]);

  const ctaLabel = !identityConfigured
    ? 'Continue'
    : googleEnabled
      ? 'Continue with Google / Mobile'
      : // Never name a sign-in method this build cannot offer.
        'Continue with Mobile';

  return (
    <View style={styles.splashRoot}>
      {SPLASH_SLIDES.map((s, i) => (
        <Animated.View
          key={s.title}
          pointerEvents="none"
          style={[StyleSheet.absoluteFill, { opacity: fades[i] }]}
        >
          <LinearGradient colors={s.gradient} style={StyleSheet.absoluteFill} />
        </Animated.View>
      ))}

      <SafeAreaView style={styles.flex} edges={['top', 'bottom']}>
        <View style={styles.splashBody}>
          <View style={styles.wordmark}>
            <Text style={styles.wordTimes}>Times</Text>
            <Text style={styles.wordHealth}>Health+</Text>
          </View>

          <View>
            <ScrollView
              ref={pager}
              horizontal
              pagingEnabled
              showsHorizontalScrollIndicator={false}
              // Full-bleed pages so a swipe can start anywhere across the screen.
              style={styles.pager}
              onTouchStart={() => setHolding(true)}
              onTouchEnd={() => setHolding(false)}
              onTouchCancel={() => setHolding(false)}
              onScrollEndDrag={() => setHolding(false)}
              onMomentumScrollEnd={(e) => {
                const i = Math.round(e.nativeEvent.contentOffset.x / width);
                setSlide(Math.max(0, Math.min(SPLASH_SLIDES.length - 1, i)));
              }}
            >
              {SPLASH_SLIDES.map((s) => (
                <View key={s.title} style={[styles.splashPage, { width }]}>
                  <Text style={styles.splashTitle}>{s.title}</Text>
                  <Text style={styles.splashText}>{s.body}</Text>
                </View>
              ))}
            </ScrollView>

            <Indicators
              count={SPLASH_SLIDES.length}
              active={slide}
              activeColor={colors.paperWhite}
              // Color(0x44FFFFFF) in the design.
              idleColor="#44FFFFFF"
              onPick={goTo}
              style={{ marginTop: spacing['6xl'] }}
            />
          </View>

          <View style={styles.startCard}>
            <Text style={styles.startTitle}>Get Started</Text>
            <Text style={styles.startSub}>One login across Yoga, Marathon & Diet.</Text>
            <Pressable
              style={styles.startCta}
              onPress={() => onContinue(slide)}
              accessibilityRole="button"
            >
              <Text style={styles.startCtaLabel}>{ctaLabel}</Text>
            </Pressable>
          </View>
        </View>
      </SafeAreaView>
    </View>
  );
}

/** The design's bar indicators: 4dp tall, 2dp corners, 6dp apart, active 28 wide. */
function Indicators({
  count,
  active,
  activeColor,
  idleColor,
  onPick,
  style,
}: {
  count: number;
  active: number;
  activeColor: string;
  idleColor: string;
  onPick: (i: number) => void;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <View style={[styles.indicators, style]}>
      {Array.from({ length: count }, (_, i) => (
        <Pressable
          key={i}
          onPress={() => onPick(i)}
          // The bar is 4dp tall; the touch target must not be.
          hitSlop={{ top: 14, bottom: 14, left: 3, right: 3 }}
          accessibilityRole="button"
          accessibilityLabel={`Show slide ${i + 1} of ${count}`}
          accessibilityState={{ selected: i === active }}
          style={[
            styles.indicator,
            { width: i === active ? 28 : 8, backgroundColor: i === active ? activeColor : idleColor },
          ]}
        />
      ))}
    </View>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Sign-in
// ─────────────────────────────────────────────────────────────────────────────

function AuthScreen({ initialSlide }: { initialSlide: number }) {
  const insets = useSafeAreaInsets();
  const scroller = useRef<ScrollView>(null);
  const [slide, setSlide] = useState(initialSlide % HIGHLIGHTS.length);
  const h = HIGHLIGHTS[slide]!;

  // The fields sit at the bottom of the card; bring them up with the keyboard
  // rather than leaving the highlight filling the visible area.
  useEffect(() => {
    const sub = Keyboard.addListener('keyboardDidShow', () =>
      scroller.current?.scrollToEnd({ animated: true }),
    );
    return () => sub.remove();
  }, []);

  return (
    <View style={styles.authRoot}>
      <SafeAreaView style={styles.flex} edges={['top']}>
        <KeyboardAvoidingView
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
          style={styles.flex}
        >
          <ScrollView
            ref={scroller}
            contentContainerStyle={styles.authScroll}
            keyboardShouldPersistTaps="handled"
            showsVerticalScrollIndicator={false}
            bounces={false}
          >
            {/* No auto-advance here: the design steps it by hand ("Next
                Highlight →"), and text shifting while someone types a phone
                number is a distraction. */}
            <View style={styles.highlight}>
              <TagPill label="ONE TH+ LOGIN" tone="SAGE" />
              <Text style={styles.highlightEmoji}>{h.emoji}</Text>
              <Text style={styles.highlightTitle}>{h.title}</Text>
              <Text style={styles.highlightBody}>{h.body}</Text>
              <Indicators
                count={HIGHLIGHTS.length}
                active={slide}
                activeColor={colors.coralBrand}
                idleColor="rgba(255,255,255,0.3)"
                onPick={setSlide}
                style={{ marginTop: spacing['5xl'] }}
              />
            </View>

            <View style={[styles.authCard, { paddingBottom: spacing['6xl'] + insets.bottom }]}>
              <AuthPanel onNextHighlight={() => setSlide((s) => (s + 1) % HIGHLIGHTS.length)} />
              {/* Compliance line — ours, not the design's; kept quiet. */}
              <Text style={styles.legal}>By continuing you agree to our Privacy & Terms.</Text>
            </View>
          </ScrollView>
        </KeyboardAvoidingView>
      </SafeAreaView>
    </View>
  );
}

type Mode = 'menu' | 'otp' | 'email';

function AuthPanel({ onNextHighlight }: { onNextHighlight: () => void }) {
  const signInPersona = useSessionStore((s) => s.signInPersona);
  const [mode, setMode] = useState<Mode>('menu');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [phone, setPhone] = useState('');
  const [otp, setOtp] = useState('');
  const phoneSession = useRef<PhoneSession | null>(null);
  const phoneInput = useRef<TextInput>(null);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [emailMode, setEmailMode] = useState<'signIn' | 'signUp'>('signIn');

  /** Runs an auth step with a shared busy/error convention. */
  const run = async (label: string, fn: () => Promise<void>) => {
    setBusy(label);
    setError(null);
    setNotice(null);
    try {
      await fn();
    } catch (err) {
      const e = err instanceof AuthError ? err : new AuthError('Sign-in failed. Please try again.');
      if (!e.cancelled) setError(e.message);
    } finally {
      setBusy(null);
    }
  };

  const e164 = toIndianE164(phone);

  // ── OTP entry ─────────────────────────────────────────────────────────────
  if (mode === 'otp') {
    return (
      <View>
        <Text style={styles.cardTitle}>Enter the code</Text>
        <Text style={styles.cardSub}>OTP sent to {e164}</Text>
        <View style={styles.stack}>
          <OutlinedField
            label="6-digit code"
            value={otp}
            onChangeText={(t) => setOtp(t.replace(/\D/g, '').slice(0, 6))}
            keyboardType="number-pad"
            textContentType="oneTimeCode"
            autoComplete="sms-otp"
            style={styles.otpInput}
            autoFocus
          />
          <ErrorLine error={error} />
          <DarkButton
            label="Verify & Log In"
            busy={busy === 'verify'}
            disabled={otp.length !== 6}
            onPress={() => void run('verify', () => phoneSession.current!.confirm(otp))}
          />
        </View>
        <View style={styles.links}>
          <TextLink
            label="Use a different number"
            center
            onPress={() => {
              setMode('menu');
              setOtp('');
              setError(null);
            }}
          />
        </View>
      </View>
    );
  }

  // ── Email ─────────────────────────────────────────────────────────────────
  if (mode === 'email') {
    const valid = /\S+@\S+\.\S+/.test(email) && password.length >= 6;
    return (
      <View>
        <Text style={[styles.cardTitle, { marginBottom: spacing['4xl'] }]}>
          {emailMode === 'signIn' ? 'Log in with email' : 'Create your account'}
        </Text>
        <View style={styles.stack}>
          <OutlinedField
            label="Email address"
            value={email}
            onChangeText={setEmail}
            keyboardType="email-address"
            autoCapitalize="none"
            autoComplete="email"
          />
          <OutlinedField
            label={emailMode === 'signIn' ? 'Password' : 'Choose a password (6+ characters)'}
            value={password}
            onChangeText={setPassword}
            secureTextEntry
            autoComplete={emailMode === 'signIn' ? 'password' : 'new-password'}
          />
          <ErrorLine error={error} />
          {notice ? <Text style={styles.notice}>{notice}</Text> : null}
          <DarkButton
            label={emailMode === 'signIn' ? 'Log In' : 'Create Account'}
            busy={busy === 'email'}
            disabled={!valid}
            onPress={() => void run('email', () => signInWithEmail(email, password, emailMode))}
          />
        </View>
        <View style={[styles.links, styles.rowBetween]}>
          <TextLink
            label={emailMode === 'signIn' ? 'New here? Create account' : 'Have an account? Log in'}
            onPress={() => {
              setEmailMode(emailMode === 'signIn' ? 'signUp' : 'signIn');
              setError(null);
            }}
          />
          {emailMode === 'signIn' ? (
            <TextLink
              label="Forgot password?"
              disabled={!/\S+@\S+\.\S+/.test(email)}
              onPress={() =>
                void run('reset', async () => {
                  await sendPasswordReset(email);
                  setNotice(`Password reset link sent to ${email.trim()}.`);
                })
              }
            />
          ) : null}
        </View>
        <TextLink
          label="← Other ways to log in"
          center
          onPress={() => {
            setMode('menu');
            setError(null);
          }}
        />
      </View>
    );
  }

  // ── Menu ──────────────────────────────────────────────────────────────────
  return (
    <View>
      <View style={styles.cardHeader}>
        <Text style={[styles.cardTitle, styles.shrink]}>Log In or Sign Up</Text>
        <Pressable onPress={onNextHighlight} style={styles.textButton} accessibilityRole="button">
          <Text style={styles.nextHighlight}>Next Highlight →</Text>
        </Pressable>
      </View>
      <Text style={styles.cardSub}>
        One account connects your Yoga subscription, race bibs & dietitian consults.
      </Text>

      {identityConfigured ? (
        <>
          <View style={styles.stack}>
            {googleEnabled ? (
              <Pressable
                style={styles.googleBtn}
                disabled={busy !== null}
                onPress={() => void run('google', signInWithGoogle)}
                accessibilityRole="button"
              >
                {busy === 'google' ? (
                  <ActivityIndicator color={colors.textPrimary} />
                ) : (
                  <Text style={[styles.buttonLabel, { color: colors.textPrimary }]}>
                    G  Continue with Google
                  </Text>
                )}
              </Pressable>
            ) : null}

            <OutlinedField
              inputRef={phoneInput}
              label="Mobile number"
              prefix="+91"
              value={phone}
              onChangeText={(t) => setPhone(t.replace(/\D/g, '').slice(0, 10))}
              keyboardType="phone-pad"
              autoComplete="tel"
            />

            <DarkButton
              label="Send OTP & Log In"
              busy={busy === 'otp'}
              disabled={!e164}
              onPress={() =>
                void run('otp', async () => {
                  phoneSession.current = await startPhoneSignIn(e164!);
                  setMode('otp');
                })
              }
            />
            <ErrorLine error={error} />
          </View>

          <View style={styles.links}>
            {/* The design jumps straight to onboarding here. Login is mandatory
                (§5), so the honest version of "new here?" is: sign up with your
                number — the Gate then routes every new account through the
                4-step personalisation. Tapping it starts that by focusing the
                number field. */}
            <TextLink
              label="New here? Walk through 4-step personalisation →"
              size={11.5}
              center
              onPress={() => phoneInput.current?.focus()}
            />
            <TextLink
              label="Continue with email instead"
              center
              onPress={() => {
                setMode('email');
                setError(null);
              }}
            />
          </View>
        </>
      ) : (
        <Text style={styles.notice}>Sign-in is not configured in this build.</Text>
      )}

      {/* QA personas: dev builds and test APKs only. A server must also run with
          ALLOW_DEV_TOKENS=true to accept them, and refuses to in production. */}
      {devSignInEnabled ? (
        <View style={styles.devBox}>
          <Text style={[type.labelSmall, { color: colors.amberWarn, marginBottom: spacing.md }]}>
            {upper('Test personas · not in release builds')}
          </Text>
          {DEV_PERSONAS.map((p) => (
            <Pressable
              key={p.token}
              style={styles.devRow}
              onPress={() => void signInPersona(p.token)}
              accessibilityRole="button"
            >
              <Text style={[type.bodyLarge, { color: colors.textPrimary }]}>{p.label}</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
    </View>
  );
}

/**
 * LoginScreenKt's primary action: a 50dp carbon950 Button, 12dp corners.
 * Disabled colours are M3's (onSurface at 12% / 38%), not a faded black.
 */
function DarkButton({
  label,
  busy,
  disabled,
  onPress,
}: {
  label: string;
  busy: boolean;
  disabled?: boolean;
  onPress: () => void;
}) {
  const off = disabled || busy;
  return (
    <Pressable
      style={[styles.darkBtn, disabled && !busy && styles.darkBtnDisabled]}
      disabled={off}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityState={{ disabled: off, busy }}
    >
      {busy ? (
        <ActivityIndicator color={colors.paperWhite} />
      ) : (
        <Text
          style={[styles.buttonLabel, { color: disabled ? 'rgba(26,20,24,0.38)' : colors.paperWhite }]}
        >
          {label}
        </Text>
      )}
    </Pressable>
  );
}

/** An M3 TextButton in the design's plum link style. */
function TextLink({
  label,
  onPress,
  size = 12,
  center,
  disabled,
}: {
  label: string;
  onPress: () => void;
  size?: number;
  center?: boolean;
  disabled?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={[styles.textButton, center && styles.selfCenter]}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
    >
      <Text style={[styles.link, { fontSize: size }, disabled && { color: colors.textMuted }]}>
        {label}
      </Text>
    </Pressable>
  );
}

function ErrorLine({ error }: { error: string | null }) {
  if (!error) return null;
  return (
    <View style={styles.errorBox} accessibilityLiveRegion="polite">
      <Ionicons name="alert-circle" size={16} color={colors.crimsonAlert} />
      <Text style={[type.bodyMedium, { color: colors.crimsonAlert, flex: 1 }]}>{error}</Text>
    </View>
  );
}

/**
 * The design's M3 OutlinedTextField on the light card: 56dp, 12dp corners,
 * borderRule outline that turns primary (coral) and 2dp on focus, and a label
 * that sits inside until focused or filled, then floats onto the border.
 * A prefix (the +91 dial code) shows only once the label has floated, as in M3.
 */
function OutlinedField({
  label,
  prefix,
  inputRef,
  value,
  onFocus,
  onBlur,
  style,
  ...input
}: TextInputProps & { label: string; prefix?: string; inputRef?: Ref<TextInput> }) {
  const [focused, setFocused] = useState(false);
  const floated = focused || !!value;
  const lift = useRef(new Animated.Value(floated ? 1 : 0)).current;

  useEffect(() => {
    Animated.timing(lift, { toValue: floated ? 1 : 0, duration: 150, useNativeDriver: false }).start();
  }, [floated, lift]);

  const bw = focused ? 2 : 1;
  const tint = focused ? colors.coralBrand : colors.borderRule;
  return (
    <View
      style={[
        styles.field,
        // RN borders take layout space (Compose's don't): keep the text at 16dp.
        { borderWidth: bw, borderColor: tint, paddingHorizontal: 16 - bw },
      ]}
    >
      {floated && prefix ? <Text style={styles.fieldPrefix}>{prefix}</Text> : null}
      <TextInput
        ref={inputRef}
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
            color: focused ? colors.coralBrand : colors.textSecondary,
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
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  shrink: { flexShrink: 1 },
  selfCenter: { alignSelf: 'center' },

  // ── Splash (PreLoginSplashScreen) ─────────────────────────────────────────
  splashRoot: { flex: 1, backgroundColor: SPLASH_SLIDES[0]!.gradient[1] },
  splashBody: { flex: 1, padding: spacing['6xl'], justifyContent: 'space-between' },
  wordmark: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
  wordTimes: { fontFamily: FONTS.sans, fontSize: 24, lineHeight: 30, fontWeight: '700', color: colors.paperWhite },
  wordHealth: { fontFamily: FONTS.sans, fontSize: 24, lineHeight: 30, fontWeight: '800', color: colors.coralBrand },
  pager: { marginHorizontal: -spacing['6xl'], flexGrow: 0 },
  // Pages stretch to the tallest; bottom-align so short copy hugs the bars.
  splashPage: { paddingHorizontal: spacing['6xl'], justifyContent: 'flex-end' },
  splashTitle: {
    fontFamily: FONTS.serif,
    fontSize: 38,
    lineHeight: 42,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  splashText: {
    fontFamily: FONTS.sans,
    fontSize: 14,
    lineHeight: 20,
    color: 'rgba(255,255,255,0.8)',
    marginTop: spacing.lg,
  },
  indicators: { flexDirection: 'row', gap: spacing.sm },
  indicator: { height: 4, borderRadius: 2 },
  startCard: { backgroundColor: colors.paperWhite, borderRadius: radii.xl, padding: spacing['4xl'] },
  startTitle: {
    fontFamily: FONTS.serif,
    fontSize: 20,
    lineHeight: 25,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  startSub: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 22,
    color: colors.textMuted,
    marginBottom: spacing.xxl,
  },
  startCta: {
    height: 48,
    borderRadius: radii.md,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing['6xl'],
  },
  startCtaLabel: {
    fontFamily: FONTS.sans,
    fontSize: 13.5,
    lineHeight: 17,
    fontWeight: '700',
    letterSpacing: 0.2,
    color: colors.paperWhite,
  },

  // ── Sign-in (LoginScreenKt) ───────────────────────────────────────────────
  authRoot: { flex: 1, backgroundColor: AUTH_BG },
  authScroll: { flexGrow: 1 },
  highlight: { flexGrow: 1, justifyContent: 'center', padding: 26 },
  highlightEmoji: { fontSize: 44, lineHeight: 54, marginTop: spacing.xxl },
  highlightTitle: {
    // displayLarge (40sp Medium serif, -0.5) with the design's 42sp line.
    ...type.displayLarge,
    lineHeight: 42,
    color: colors.paperWhite,
    marginTop: spacing.md,
  },
  highlightBody: {
    fontFamily: FONTS.sans,
    fontSize: 13.5,
    lineHeight: 19,
    color: 'rgba(255,255,255,0.8)',
    marginTop: spacing.md,
  },
  authCard: {
    backgroundColor: colors.canvasBg,
    borderTopLeftRadius: radii.sheet,
    borderTopRightRadius: radii.sheet,
    padding: spacing['6xl'],
  },
  cardHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  cardTitle: { ...type.headlineLarge, color: colors.textPrimary },
  cardSub: { ...type.bodySmall, color: colors.textMuted, marginBottom: spacing['4xl'] },
  nextHighlight: { fontFamily: FONTS.sans, fontSize: 11, lineHeight: 16, color: colors.plumBrand },
  // The design's 10dp rhythm between Google, the number field and Send OTP.
  stack: { gap: spacing.lg },
  // …and 6dp from the last button to the text links under it.
  links: { marginTop: spacing.sm },
  googleBtn: {
    height: 48,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    alignItems: 'center',
    justifyContent: 'center',
  },
  darkBtn: {
    height: 50,
    borderRadius: radii.md,
    backgroundColor: colors.carbon950,
    alignItems: 'center',
    justifyContent: 'center',
  },
  darkBtnDisabled: { backgroundColor: 'rgba(26,20,24,0.12)' },
  // M3 Button labelLarge: 13sp bold, +0.2.
  buttonLabel: { fontFamily: FONTS.sans, fontSize: 13, lineHeight: 17, fontWeight: '700', letterSpacing: 0.2 },
  // M3 TextButton: 40dp minimum, 12×8 content padding.
  textButton: { minHeight: 40, paddingHorizontal: spacing.xl, paddingVertical: spacing.md, justifyContent: 'center' },
  link: { fontFamily: FONTS.sans, lineHeight: 16, fontWeight: '600', color: colors.plumBrand, textAlign: 'center' },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', flexWrap: 'wrap' },
  notice: { ...type.bodyMedium, color: colors.textSecondary },
  errorBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: colors.coralTint,
    borderRadius: radii.sm,
    padding: spacing.xl,
  },
  otpInput: { fontSize: 20, letterSpacing: 8 },
  legal: { ...type.bodySmall, color: colors.textMuted, textAlign: 'center', marginTop: spacing.xl },
  devBox: {
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.amberWarn,
    borderRadius: radii.md,
    padding: spacing['3xl'],
    marginTop: spacing.xl,
  },
  devRow: { paddingVertical: spacing.lg },

  // ── OutlinedField ─────────────────────────────────────────────────────────
  field: {
    height: 56,
    borderRadius: radii.md,
    flexDirection: 'row',
    alignItems: 'center',
  },
  fieldPrefix: { fontFamily: FONTS.sans, fontSize: 16, color: colors.textSecondary, marginRight: spacing.md },
  fieldInput: {
    flex: 1,
    alignSelf: 'stretch',
    padding: 0,
    fontFamily: FONTS.sans,
    fontSize: 16,
    color: colors.textPrimary,
  },
  fieldLabel: { position: 'absolute', paddingHorizontal: spacing.xs, fontFamily: FONTS.sans, maxWidth: '90%' },
});
