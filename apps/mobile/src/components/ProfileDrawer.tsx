import { useEffect, useRef, useState, type ReactNode, type Ref } from 'react';
import {
  ActivityIndicator,
  Alert,
  Animated,
  Easing,
  KeyboardAvoidingView,
  Linking,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type KeyboardTypeOptions,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { Concern, HealthGoal, MarathonEntitlement, UserProfile } from '@th/types';
import { ApiRequestError, api } from '@/api/client';
import { useContent, useSession, useUpdateProfile } from '@/api/hooks';
import { toIndianE164 } from '@/lib/identity';
import { PRIVACY_URL, SUPPORT_WHATSAPP_URL as SUPPORT_URL } from '@/lib/links';
import { CONCERNS, GOALS, concernLabel, formatPhone, goalLabel } from '@/lib/profileLabels';
import { useSessionStore } from '@/store/session';
import { FONTS, colors, radii } from '@/theme';
import { TagPill } from './TagPill';

/**
 * PROFILE — PRD §10. Opened from the avatar in the shared TopHeader, so it is
 * one tap away on every tab — and deliberately still not a tab.
 *
 * Drawn as the design's ProfileSheet: a paper-white ModalBottomSheet with a
 * drag handle, header → MY PRODUCTS → ACCOUNT & SETTINGS → Log Out. Personal
 * details, their edit forms and Help & Support open INSIDE the sheet (a back
 * arrow returns), so nothing stacks a second modal on this one.
 *
 * "My Products" is the reason this screen exists: one place showing the yoga
 * subscription and marathon registrations a user holds. Diet never appears
 * here, because diet purchases cannot be mapped to app accounts in V1 (§9).
 */

type EditField = 'name' | 'email' | 'phone' | 'dob' | 'gender' | 'goal' | 'concern';
type Screen = 'main' | 'personal' | 'help' | EditField;

/** PATCH /profile's gender enum, with the wording people pick from. */
const GENDERS = [
  { value: 'FEMALE', label: 'Female' },
  { value: 'MALE', label: 'Male' },
  { value: 'NON_BINARY', label: 'Non-binary' },
  { value: 'PREFER_NOT_TO_SAY', label: 'Prefer not to say' },
] as const;
type Gender = (typeof GENDERS)[number]['value'];

/** Everything PATCH /profile accepts (apps/api session.ts profileSchema). */
interface ProfilePatch {
  name?: string;
  email?: string;
  phone?: string;
  /** ISO datetime at UTC midnight. */
  dob?: string;
  gender?: Gender;
  units?: 'METRIC' | 'IMPERIAL';
  healthGoal?: HealthGoal;
  concern?: Concern;
}

/**
 * Answers about the app itself, alongside the yoga FAQs the API serves. Every
 * one describes what this build actually does — keep them in step with it.
 */
const APP_FAQS = [
  {
    question: 'How do I delete my account?',
    answer:
      'Open your profile and tap “Delete my account” at the bottom. It permanently removes your profile, attendance history, runs and saved sessions, and can’t be undone. Active subscriptions and race registrations are not refunded automatically — message support first if you need a refund.',
  },
  {
    question: 'Who do I contact about a payment or refund?',
    answer:
      'Message TimesHealth+ support on WhatsApp from Help & Support. Paid workshop seats can’t be cancelled in the app either — support handles the cancellation and the refund together.',
  },
  {
    question: 'Why am I not getting app notifications?',
    answer:
      'App alerts need notification permission. Profile → Notifications opens your phone’s settings for TimesHealth+, where you can switch them on. Class reminders and race-day updates also come on WhatsApp, so the essentials still reach you.',
  },
  {
    question: 'Will my race pass work without signal at the venue?',
    answer:
      'Yes. Open your race in the app once while you have signal and your digital bib and QR pass are saved on this phone, so they open at the gate with no network. Saved passes are removed when you sign out.',
  },
  {
    question: 'Can I change the email or mobile number I sign in with?',
    answer:
      'Not from the app — it’s marked “Used to sign in” under Personal details. Your other contact detail can be edited there.',
  },
];

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export function ProfileDrawer({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { data } = useSession();
  const signOut = useSessionStore((s) => s.signOut);
  const updateUnits = useUpdateProfile();
  const [screen, setScreen] = useState<Screen>('main');
  const metric = data?.profile.units !== 'IMPERIAL';

  // Every opening starts on the overview, not wherever it was last left.
  useEffect(() => {
    if (visible) setScreen('main');
  }, [visible]);

  // The scrim fades (Modal), the sheet rises — as a Material bottom sheet does.
  const rise = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (!visible) return;
    rise.setValue(0);
    Animated.timing(rise, {
      toValue: 1,
      duration: 260,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [visible, rise]);
  const translateY = rise.interpolate({ inputRange: [0, 1], outputRange: [640, 0] });

  /** Leave the sheet for another screen. */
  const go = (navigate: () => void) => {
    onClose();
    navigate();
  };
  const openPaywall = () =>
    go(() => router.push({ pathname: '/paywall', params: { productId: 'yoga_annual' } }));

  /**
   * Account deletion — a Google Play requirement since 2023 and a DPDP right.
   * Two-step on purpose: it cascades attendance history, runs and
   * registrations, and cannot be undone.
   */
  const confirmDelete = () => {
    // Name exactly what paid-for access goes with the account, so nobody
    // deletes a membership or a race entry without realising it.
    const losing = [
      data?.entitlements.yoga?.active
        ? `your Yoga membership (valid until ${formatDate(data.entitlements.yoga.expiresAt)})`
        : null,
      ...(data?.entitlements.marathon ?? [])
        .filter((m) => m.status === 'UPCOMING' || m.status === 'RACE_DAY')
        .map((m) => `your ${m.eventName} registration`),
    ].filter((x): x is string => x !== null);
    Alert.alert(
      'Delete your account?',
      'This permanently removes your profile, attendance history, runs and saved sessions.' +
        (losing.length
          ? `\n\nYou will also lose ${losing.join(', ')}. These are not refunded automatically — message support first if you need a refund.`
          : ''),
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete permanently',
          style: 'destructive',
          onPress: async () => {
            try {
              await api.del('/account');
            } catch {
              Alert.alert(
                'Couldn’t delete your account',
                'Check your connection and try again. Nothing has been deleted.',
              );
              return;
            }
            onClose();
            await signOut({ accountDeleted: true });
            router.replace('/login');
          },
        },
      ],
    );
  };

  const handleSignOut = async () => {
    onClose();
    await signOut();
    router.replace('/login');
  };

  const toggleUnits = () =>
    updateUnits.mutate(
      { units: metric ? 'IMPERIAL' : 'METRIC' },
      {
        onError: () =>
          Alert.alert('Couldn’t change units', 'Check your connection and try again.'),
      },
    );

  const profile = data?.profile;
  const yoga = data?.entitlements.yoga ?? null;
  const races = data?.entitlements.marathon ?? [];
  const completed = races.filter((r) => r.status === 'COMPLETED');
  const active = races.filter((r) => r.status !== 'COMPLETED');

  let body: ReactNode;
  if (screen === 'personal') {
    body = (
      <PersonalDetails
        profile={profile}
        races={races}
        onBack={() => setScreen('main')}
        onClose={onClose}
        onEdit={setScreen}
        onOpenRace={(eventId) =>
          go(() => router.push({ pathname: '/race/[eventId]', params: { eventId, edit: 'participant' } }))
        }
      />
    );
  } else if (screen === 'help') {
    body = <HelpAndSupport onBack={() => setScreen('main')} onClose={onClose} />;
  } else if (screen !== 'main') {
    body = profile ? (
      <EditPanel
        key={screen}
        field={screen}
        profile={profile}
        onBack={() => setScreen('personal')}
        onClose={onClose}
      />
    ) : null;
  } else {
    const name = profile?.name?.trim() || null;
    const contact = [profile?.phone ? formatPhone(profile.phone) : null, profile?.email]
      .filter(Boolean)
      .join(' · ');
    body = (
      <>
        {/* Header: 54dp plumDeep initial, serif 20 Bold name, phone · email. */}
        <View style={styles.header}>
          <View style={styles.headerId}>
            <View style={styles.avatar}>
              <Text style={styles.avatarInitial}>{(name ?? 'P').charAt(0).toUpperCase()}</Text>
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.name} numberOfLines={1}>
                {name ?? 'Your profile'}
              </Text>
              {contact ? (
                <Text style={styles.contact} numberOfLines={1}>
                  {contact}
                </Text>
              ) : null}
            </View>
          </View>
          <CloseButton onPress={onClose} />
        </View>
        <View style={{ height: 18 }} />

        {profile ? (
          <Pressable
            style={styles.completion}
            onPress={() => setScreen('personal')}
            accessibilityRole="button"
            accessibilityLabel={`Profile ${profile.profileCompletion}% complete. Open personal details.`}
          >
            <Text style={styles.completionText}>Profile {profile.profileCompletion}% complete</Text>
            <View style={styles.barTrack}>
              <View style={[styles.barFill, { width: `${profile.profileCompletion}%` }]} />
            </View>
          </Pressable>
        ) : null}

        <SectionLabel>MY PRODUCTS</SectionLabel>

        {yoga?.active ? (
          <ProductCard
            tile={{ emoji: '🧘', bg: colors.plumTint }}
            title="Yoga Membership"
            // "Renews" only when something will actually charge a renewal.
            subtitle={`${yoga.planLabel} · ${yoga.autoRenews ? 'Renews' : 'Valid until'} ${formatDate(yoga.expiresAt)}`}
            onPress={openPaywall}
            right={
              <View style={styles.rightGroup}>
                <TagPill label="ACTIVE" tone="EMERALD" />
                {/* #61: the paywall greets a member with "Extend your membership". */}
                <SmallButton label="Extend" tone="sand" onPress={openPaywall} />
              </View>
            }
          />
        ) : yoga ? (
          <ProductCard
            tile={{ emoji: '🧘', bg: colors.plumTint }}
            title="Yoga Membership"
            subtitle={`Expired ${formatDate(yoga.expiresAt)}`}
            onPress={openPaywall}
            right={<SmallButton label="Renew" tone="plum" onPress={openPaywall} />}
          />
        ) : (
          <ProductCard
            tile={{ emoji: '🧘', bg: colors.plumTint }}
            title="Yoga Membership"
            subtitle="Not subscribed · 8 live classes daily"
            onPress={openPaywall}
            // A visible way in, not just a status (§4: never a dead end).
            right={<SmallButton label="Explore" tone="plum" onPress={openPaywall} />}
          />
        )}

        {active.length === 0 ? (
          <ProductCard
            tile={{ emoji: '🏃', bg: colors.coralTint }}
            title="Marathon Registrations"
            subtitle="No active registrations"
            onPress={() => go(() => router.push('/(tabs)/marathon'))}
            right={<TagPill label="NONE" tone="NEUTRAL" />}
          />
        ) : (
          active.map((r) => (
            <ProductCard
              key={r.eventId}
              tile={{ emoji: '🏃', bg: colors.coralTint }}
              // One registration reads as the design: "Marathon Registrations"
              // over "event · Bib #". With several, the event names lead.
              title={active.length === 1 ? 'Marathon Registrations' : r.eventName}
              subtitle={
                active.length === 1
                  ? `${r.eventName} · ${bibOrRef(r)}`
                  : `${r.category} ${tierLabel(r)} · ${bibOrRef(r)}`
              }
              onPress={() =>
                go(() => router.push({ pathname: '/race/[eventId]', params: { eventId: r.eventId } }))
              }
              right={<TagPill label="REGISTERED" tone="CORAL" />}
            />
          ))
        )}

        {completed.map((r) => {
          const openResult = () =>
            go(() =>
              router.push({ pathname: '/race/[eventId]/results', params: { eventId: r.eventId } }),
            );
          return (
            <ProductCard
              key={r.eventId}
              title={r.eventName}
              titleSize={13}
              subtitle={`${r.category} ${tierLabel(r)} · Result & certificate`}
              onPress={openResult}
              right={<SmallButton label="Result" tone="sand" onPress={openResult} />}
            />
          );
        })}

        <Text style={styles.dietNote}>
          Diet consultations are arranged with your dietitian on WhatsApp, so they don’t appear
          here.
        </Text>

        <SectionLabel>ACCOUNT &amp; SETTINGS</SectionLabel>
        <SettingRow
          title="Personal details"
          subtitle="Name, DOB, gender and marathon medical contacts"
          onPress={() => setScreen('personal')}
        />
        <SettingRow
          title="Notifications"
          subtitle="Class reminders & race-day alerts"
          // The OS owns this switch once asked; send people to it rather
          // than pretending an in-app toggle can override a denial.
          onPress={() => void Linking.openSettings()}
        />
        <SettingRow
          title="Units & Measurement"
          subtitle={
            updateUnits.isPending
              ? 'Switching…'
              : metric
                ? 'Kilometres and metric pace'
                : 'Miles and imperial pace'
          }
          onPress={updateUnits.isPending ? undefined : toggleUnits}
          trailing={metric ? 'km' : 'mi'}
        />
        {/* §10 lists language; English only in V1 — information, not a dead control. */}
        <SettingRow title="Language" subtitle="English" />
        <SettingRow
          title="Help & Support"
          subtitle="FAQs and WhatsApp support"
          onPress={() => setScreen('help')}
        />
        <SettingRow
          title="Privacy & Terms"
          subtitle="TimesHealth+ policy and data guidelines"
          onPress={() => void Linking.openURL(PRIVACY_URL)}
        />

        <View style={{ height: 18 }} />
        <Pressable
          style={({ pressed }) => [styles.logout, pressed && styles.pressed]}
          onPress={() => void handleSignOut()}
          accessibilityRole="button"
        >
          <Text style={styles.logoutText}>Log Out of TimesHealth+</Text>
        </Pressable>

        {/* A store requirement the design doesn't draw: kept, but quiet and last. */}
        <Pressable onPress={confirmDelete} style={styles.deleteBtn} accessibilityRole="button" hitSlop={6}>
          <Text style={styles.deleteText}>Delete my account</Text>
        </Pressable>
      </>
    );
  }

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      // Android back steps out of a sub-page before it closes the sheet.
      onRequestClose={() =>
        screen === 'main'
          ? onClose()
          : setScreen(screen === 'personal' || screen === 'help' ? 'main' : 'personal')
      }
    >
      {/* 'padding' on both platforms: the modal draws edge to edge, so Android
          does not resize it for the keyboard — the padding lifts the sheet,
          and settles back to zero wherever the window does resize. */}
      <KeyboardAvoidingView behavior="padding" style={styles.root}>
        <Pressable
          style={styles.scrim}
          onPress={onClose}
          accessibilityRole="button"
          accessibilityLabel="Close profile"
        />
        <Animated.View style={[styles.sheet, { transform: [{ translateY }] }]}>
          <View style={styles.handle} />
          <ScrollView
            key={screen}
            style={{ flexGrow: 0 }}
            contentContainerStyle={[styles.body, { paddingBottom: 24 + insets.bottom }]}
            keyboardShouldPersistTaps="handled"
            showsVerticalScrollIndicator={false}
          >
            {body}
          </ScrollView>
        </Animated.View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

// ── Personal details (§10) ──────────────────────────────────────────────────

function PersonalDetails({
  profile,
  races,
  onBack,
  onClose,
  onEdit,
  onOpenRace,
}: {
  profile: UserProfile | undefined;
  races: MarathonEntitlement[];
  onBack: () => void;
  onClose: () => void;
  onEdit: (field: EditField) => void;
  onOpenRace: (eventId: string) => void;
}) {
  // §10 "marathon fields where held": t-shirt size and emergency contact live
  // on each registration, edited on the race page (ParticipantEditor). Only
  // while it is still upcoming — after race day there is nothing to change.
  const upcoming = races.filter((r) => r.status === 'UPCOMING');
  const gender = GENDERS.find((g) => g.value === profile?.gender)?.label ?? null;

  return (
    <>
      <SubHeader title="Personal details" onBack={onBack} onClose={onClose} />
      <SettingRow title="Name" subtitle={profile?.name || 'Not added'} onPress={() => onEdit('name')} />
      <ContactRow
        title="Email"
        value={profile?.email ?? null}
        locked={profile?.emailIsLogin ?? false}
        onPress={() => onEdit('email')}
      />
      <ContactRow
        title="Mobile"
        value={profile?.phone ? formatPhone(profile.phone) : null}
        locked={profile?.phoneIsLogin ?? false}
        onPress={() => onEdit('phone')}
      />
      <SettingRow
        title="Date of birth"
        subtitle={profile?.dob ? formatDob(profile.dob) : 'Not added'}
        onPress={() => onEdit('dob')}
      />
      <SettingRow title="Gender" subtitle={gender ?? 'Not added'} onPress={() => onEdit('gender')} />
      {/* Chosen at onboarding; they order the Home feed and are shown as set. */}
      <SettingRow title="Health goal" subtitle={goalLabel(profile?.healthGoal)} onPress={() => onEdit('goal')} />
      <SettingRow title="Focus area" subtitle={concernLabel(profile?.concern)} onPress={() => onEdit('concern')} />

      {upcoming.length ? (
        <>
          <View style={{ height: 18 }} />
          <SectionLabel>MARATHON</SectionLabel>
          {upcoming.map((r) => (
            <SettingRow
              key={r.eventId}
              title="Race participant details"
              subtitle={`${r.eventName} · T-shirt size & emergency contact`}
              onPress={() => onOpenRace(r.eventId)}
            />
          ))}
        </>
      ) : null}
    </>
  );
}

/**
 * The sign-in email / phone belongs to the identity provider: shown read-only
 * with a note, never with an edit affordance (PATCH answers 409 if tried).
 */
function ContactRow({
  title,
  value,
  locked,
  onPress,
}: {
  title: string;
  value: string | null;
  locked: boolean;
  onPress: () => void;
}) {
  if (!locked) return <SettingRow title={title} subtitle={value ?? 'Not added'} onPress={onPress} />;
  return (
    <View>
      <View style={styles.settingRow} accessible accessibilityLabel={`${title}, ${value ?? ''}, used to sign in`}>
        <View style={{ flex: 1 }}>
          <Text style={styles.settingTitle}>{title}</Text>
          <Text style={styles.settingSub} numberOfLines={1}>
            {value ?? '—'}
          </Text>
          <View style={styles.lockNote}>
            <MaterialIcons name="lock-outline" size={11} color={colors.textMuted} />
            <Text style={styles.lockText}>Used to sign in</Text>
          </View>
        </View>
      </View>
      <View style={styles.divider} />
    </View>
  );
}

// ── Edit sheet ──────────────────────────────────────────────────────────────

const EDIT_COPY: Record<EditField, { title: string; hint: string }> = {
  name: { title: 'Your name', hint: 'As you’d like your instructor and dietitian to address you.' },
  email: { title: 'Email', hint: 'For receipts and race updates.' },
  phone: { title: 'Mobile number', hint: 'An Indian mobile for class reminders and race-day calls.' },
  dob: {
    title: 'Date of birth',
    hint: 'Used for race categories and age-group results. You need to be 13 or older.',
  },
  gender: { title: 'Gender', hint: 'Used for race categories and age-group results.' },
  goal: { title: 'Health goal', hint: 'Shapes what we put first on your Home feed.' },
  concern: { title: 'Focus area', hint: 'Sessions for this area lead your Home feed.' },
};

function EditPanel({
  field,
  profile,
  onBack,
  onClose,
}: {
  field: EditField;
  profile: UserProfile;
  onBack: () => void;
  onClose: () => void;
}) {
  const update = useUpdateProfile();
  const [error, setError] = useState<string | null>(null);

  const initialDob = profile.dob ? splitDob(profile.dob) : { dd: '', mm: '', yyyy: '' };
  const [text, setText] = useState(
    field === 'name'
      ? (profile.name ?? '')
      : field === 'email'
        ? (profile.email ?? '')
        : field === 'phone'
          ? (toIndianE164(profile.phone ?? '')?.slice(3) ?? profile.phone ?? '')
          : '',
  );
  const [dd, setDd] = useState(initialDob.dd);
  const [mm, setMm] = useState(initialDob.mm);
  const [yyyy, setYyyy] = useState(initialDob.yyyy);
  const [gender, setGender] = useState<Gender | null>(
    GENDERS.find((g) => g.value === profile.gender)?.value ?? null,
  );
  const [goal, setGoal] = useState<HealthGoal | null>(profile.healthGoal);
  const [concern, setConcern] = useState<Concern | null>(profile.concern);
  const mmRef = useRef<TextInput>(null);
  const yyyyRef = useRef<TextInput>(null);

  const save = () => {
    setError(null);
    let patch: ProfilePatch;
    switch (field) {
      case 'name': {
        const name = text.trim();
        if (!name) return setError('Enter your name.');
        if (name.length > 80) return setError('Keep your name under 80 characters.');
        patch = { name };
        break;
      }
      case 'email': {
        const email = text.trim();
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email)) {
          return setError('Enter a valid email address, like name@example.com.');
        }
        patch = { email };
        break;
      }
      case 'phone': {
        const phone = toIndianE164(text);
        if (!phone) return setError('Enter a valid 10-digit Indian mobile number.');
        patch = { phone };
        break;
      }
      case 'dob': {
        const parsed = parseDob(dd, mm, yyyy);
        if ('error' in parsed) return setError(parsed.error);
        patch = { dob: parsed.iso };
        break;
      }
      case 'gender':
        if (!gender) return setError('Choose one option.');
        patch = { gender };
        break;
      case 'goal':
        if (!goal) return setError('Choose one option.');
        patch = { healthGoal: goal };
        break;
      case 'concern':
        if (!concern) return setError('Choose one option.');
        patch = { concern };
        break;
      default:
        return;
    }
    update.mutate(patch, {
      onSuccess: onBack,
      onError: (e) => setError(errorText(e, field)),
    });
  };

  const copy = EDIT_COPY[field];
  return (
    <>
      <SubHeader title={copy.title} onBack={onBack} onClose={onClose} />
      <Text style={styles.editHint}>{copy.hint}</Text>

      {field === 'dob' ? (
        <View style={styles.dobRow}>
          <DobPart
            label="DD"
            value={dd}
            maxLength={2}
            onChange={(v) => {
              setDd(v);
              setError(null);
              if (v.length === 2) mmRef.current?.focus();
            }}
            autoFocus
          />
          <Text style={styles.dobSlash}>/</Text>
          <DobPart
            ref={mmRef}
            label="MM"
            value={mm}
            maxLength={2}
            onChange={(v) => {
              setMm(v);
              setError(null);
              if (v.length === 2) yyyyRef.current?.focus();
            }}
          />
          <Text style={styles.dobSlash}>/</Text>
          <DobPart
            ref={yyyyRef}
            label="YYYY"
            value={yyyy}
            maxLength={4}
            wide
            onChange={(v) => {
              setYyyy(v);
              setError(null);
            }}
            onSubmit={save}
          />
        </View>
      ) : field === 'goal' || field === 'concern' ? (
        <View style={styles.genderGrid}>
          {(field === 'goal' ? GOALS : CONCERNS).map((o) => {
            const on = (field === 'goal' ? goal : concern) === o.value;
            return (
              <Pressable
                key={o.value}
                style={[styles.option, on && styles.optionOn]}
                onPress={() => {
                  if (field === 'goal') setGoal(o.value as HealthGoal);
                  else setConcern(o.value as Concern);
                  setError(null);
                }}
                accessibilityRole="radio"
                accessibilityState={{ checked: on }}
              >
                <Text style={[styles.optionText, on && styles.optionTextOn]}>{o.label}</Text>
              </Pressable>
            );
          })}
        </View>
      ) : field === 'gender' ? (
        <View style={styles.genderGrid}>
          {GENDERS.map((g) => {
            const on = gender === g.value;
            return (
              <Pressable
                key={g.value}
                style={[styles.option, on && styles.optionOn]}
                onPress={() => {
                  setGender(g.value);
                  setError(null);
                }}
                accessibilityRole="radio"
                accessibilityState={{ checked: on }}
              >
                <Text style={[styles.optionText, on && styles.optionTextOn]}>{g.label}</Text>
              </Pressable>
            );
          })}
        </View>
      ) : (
        <TextInput
          value={text}
          onChangeText={(v) => {
            setText(v);
            setError(null);
          }}
          autoFocus
          style={[styles.input, error ? styles.inputError : null]}
          placeholder={field === 'phone' ? '10-digit mobile number' : field === 'email' ? 'name@example.com' : 'Full name'}
          placeholderTextColor={colors.textMuted}
          keyboardType={
            (field === 'phone' ? 'phone-pad' : field === 'email' ? 'email-address' : 'default') as KeyboardTypeOptions
          }
          autoCapitalize={field === 'name' ? 'words' : 'none'}
          autoCorrect={false}
          autoComplete={field === 'phone' ? 'tel' : field === 'email' ? 'email' : 'name'}
          maxLength={field === 'phone' ? 16 : field === 'email' ? 160 : 80}
          returnKeyType="done"
          onSubmitEditing={save}
          accessibilityLabel={copy.title}
        />
      )}

      {error ? (
        <Text style={styles.editError} accessibilityLiveRegion="polite">
          {error}
        </Text>
      ) : null}

      <Pressable
        style={({ pressed }) => [styles.saveBtn, pressed && styles.pressed]}
        onPress={save}
        disabled={update.isPending}
        accessibilityRole="button"
        accessibilityState={{ busy: update.isPending }}
      >
        {update.isPending ? (
          <ActivityIndicator color={colors.paperWhite} />
        ) : (
          <Text style={styles.saveText}>Save</Text>
        )}
      </Pressable>
    </>
  );
}

function DobPart({
  ref,
  label,
  value,
  maxLength,
  wide,
  onChange,
  onSubmit,
  autoFocus,
}: {
  ref?: Ref<TextInput>;
  label: string;
  value: string;
  maxLength: number;
  wide?: boolean;
  onChange: (v: string) => void;
  onSubmit?: () => void;
  autoFocus?: boolean;
}) {
  return (
    <View style={{ flex: wide ? 1.6 : 1, gap: 4 }}>
      <Text style={styles.dobLabel}>{label}</Text>
      <TextInput
        ref={ref}
        value={value}
        onChangeText={(v) => onChange(v.replace(/\D/g, ''))}
        keyboardType="number-pad"
        maxLength={maxLength}
        placeholder={label}
        placeholderTextColor={colors.textMuted}
        style={[styles.input, styles.dobInput]}
        autoFocus={autoFocus}
        returnKeyType={onSubmit ? 'done' : 'next'}
        onSubmitEditing={onSubmit}
        accessibilityLabel={`Birth ${label === 'DD' ? 'day' : label === 'MM' ? 'month' : 'year'}`}
      />
    </View>
  );
}

/** Server answers, in words for this field. */
function errorText(e: unknown, field: EditField): string {
  if (e instanceof ApiRequestError) {
    // INVALID_PHONE: the server's own number check. LOGIN_IDENTIFIER (409):
    // the sign-in email / phone can't be changed here — its message says why.
    if (e.body.code === 'INVALID_PHONE' || e.body.code === 'LOGIN_IDENTIFIER' || e.status === 0) {
      return e.message;
    }
    if (e.body.code === 'INVALID_BODY') {
      if (field === 'dob') return 'Enter a date of birth for an age between 13 and 100.';
      if (field === 'email') return 'Enter a valid email address, like name@example.com.';
    }
    return e.message || 'Couldn’t save that. Try again.';
  }
  return 'Couldn’t save that. Check your connection and try again.';
}

/** DD / MM / YYYY → ISO at UTC midnight, or why not. Age 13–100, as the server. */
function parseDob(dd: string, mm: string, yyyy: string): { iso: string } | { error: string } {
  if (!/^\d{1,2}$/.test(dd) || !/^\d{1,2}$/.test(mm) || !/^\d{4}$/.test(yyyy)) {
    return { error: 'Enter your date of birth as DD / MM / YYYY.' };
  }
  const d = Number(dd);
  const m = Number(mm);
  const y = Number(yyyy);
  // Day 0 of the next month is the last day of this one (handles leap years).
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  if (m < 1 || m > 12 || d < 1 || d > daysInMonth) {
    return { error: 'That date doesn’t exist — check the day and month.' };
  }
  const now = new Date();
  const month = now.getMonth() + 1;
  if (y > now.getFullYear() || (y === now.getFullYear() && (m > month || (m === month && d > now.getDate())))) {
    return { error: 'Your date of birth can’t be in the future.' };
  }
  let age = now.getFullYear() - y;
  if (month < m || (month === m && now.getDate() < d)) age -= 1;
  if (age < 13) return { error: 'You need to be at least 13 to use TimesHealth+.' };
  if (age > 100) return { error: 'Enter a date of birth within the last 100 years.' };
  const pad = (n: number) => String(n).padStart(2, '0');
  return { iso: `${y}-${pad(m)}-${pad(d)}T00:00:00.000Z` };
}

/** The stored DOB is a UTC midnight: read it in UTC so no timezone shifts the day. */
function splitDob(iso: string): { dd: string; mm: string; yyyy: string } {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return { dd: '', mm: '', yyyy: '' };
  const pad = (n: number) => String(n).padStart(2, '0');
  return { dd: pad(d.getUTCDate()), mm: pad(d.getUTCMonth() + 1), yyyy: String(d.getUTCFullYear()) };
}

function formatDob(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

// ── Help & Support (§10 "Support — FAQs, contact") ──────────────────────────

function HelpAndSupport({ onBack, onClose }: { onBack: () => void; onClose: () => void }) {
  const { data, isLoading } = useContent();
  return (
    <>
      <SubHeader title="Help & Support" onBack={onBack} onClose={onClose} />
      <Pressable
        style={({ pressed }) => [styles.supportCard, pressed && styles.pressed]}
        onPress={() => void Linking.openURL(SUPPORT_URL)}
        accessibilityRole="button"
      >
        <View style={[styles.tile, { backgroundColor: colors.sageTint }]}>
          <MaterialIcons name="chat" size={18} color={colors.sageBrand} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={styles.productTitle}>Chat with support on WhatsApp</Text>
          <Text style={styles.productSub}>Class link issues, payments, refunds and cancellations</Text>
        </View>
        <MaterialIcons name="chevron-right" size={24} color={colors.textMuted} />
      </Pressable>

      <View style={{ height: 18 }} />
      <SectionLabel>YOGA CLASSES</SectionLabel>
      {isLoading ? (
        <ActivityIndicator color={colors.plumBrand} style={{ marginVertical: 12 }} />
      ) : data?.yogaFaqs.length ? (
        data.yogaFaqs.map((f) => <Faq key={f.question} question={f.question} answer={f.answer} />)
      ) : (
        <Text style={styles.productSub}>These answers couldn’t load right now. Support can help on WhatsApp.</Text>
      )}

      <View style={{ height: 18 }} />
      <SectionLabel>ACCOUNT &amp; APP</SectionLabel>
      {APP_FAQS.map((f) => (
        <Faq key={f.question} question={f.question} answer={f.answer} />
      ))}
    </>
  );
}

/** The design's FaqAccordion: its own R12 card, chevron, answer on expand. */
function Faq({ question, answer }: { question: string; answer: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Pressable
      style={styles.faq}
      onPress={() => setOpen((o) => !o)}
      accessibilityRole="button"
      accessibilityState={{ expanded: open }}
    >
      <View style={styles.faqHead}>
        <Text style={styles.faqQ}>{question}</Text>
        <MaterialIcons
          name={open ? 'keyboard-arrow-up' : 'keyboard-arrow-down'}
          size={22}
          color={colors.textMuted}
        />
      </View>
      {open ? <Text style={styles.faqA}>{answer}</Text> : null}
    </Pressable>
  );
}

// ── Pieces ──────────────────────────────────────────────────────────────────

function CloseButton({ onPress }: { onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      style={styles.iconBtn}
      accessibilityRole="button"
      accessibilityLabel="Close profile"
    >
      <MaterialIcons name="close" size={24} color={colors.textMuted} />
    </Pressable>
  );
}

function SubHeader({ title, onBack, onClose }: { title: string; onBack: () => void; onClose: () => void }) {
  return (
    <View style={[styles.header, { marginBottom: 6 }]}>
      <Pressable onPress={onBack} style={[styles.iconBtn, { marginLeft: -12 }]} accessibilityRole="button" accessibilityLabel="Back">
        <MaterialIcons name="arrow-back" size={24} color={colors.textPrimary} />
      </Pressable>
      <Text style={[styles.name, { flex: 1 }]} accessibilityRole="header" numberOfLines={1}>
        {title}
      </Text>
      <CloseButton onPress={onClose} />
    </View>
  );
}

/** 10sp ExtraBold, +0.8, textMuted, then a 6dp spacer. */
function SectionLabel({ children }: { children: ReactNode }) {
  return <Text style={styles.sectionLabel}>{children}</Text>;
}

function ProductCard({
  tile,
  title,
  titleSize = 13.5,
  subtitle,
  right,
  onPress,
}: {
  tile?: { emoji: string; bg: string };
  title: string;
  titleSize?: number;
  subtitle: string;
  right: ReactNode;
  onPress: () => void;
}) {
  return (
    <Pressable
      style={({ pressed }) => [styles.productCard, pressed && styles.pressed]}
      onPress={onPress}
      accessibilityRole="button"
    >
      <View style={styles.productId}>
        {tile ? (
          <View style={[styles.tile, { backgroundColor: tile.bg }]}>
            <Text style={styles.tileEmoji}>{tile.emoji}</Text>
          </View>
        ) : null}
        <View style={{ flex: 1 }}>
          <Text style={[styles.productTitle, { fontSize: titleSize }]} numberOfLines={1}>
            {title}
          </Text>
          <Text style={styles.productSub} numberOfLines={2}>
            {subtitle}
          </Text>
        </View>
      </View>
      {right}
    </Pressable>
  );
}

/** Button h34, R8 — PlumBrand "Renew", SurfaceSand "Result" / "Extend". */
function SmallButton({ label, tone, onPress }: { label: string; tone: 'plum' | 'sand'; onPress: () => void }) {
  const plum = tone === 'plum';
  return (
    <Pressable
      style={({ pressed }) => [
        styles.smallBtn,
        { backgroundColor: plum ? colors.plumBrand : colors.surfaceSand, paddingHorizontal: plum ? 12 : 10 },
        pressed && styles.pressed,
      ]}
      onPress={onPress}
      hitSlop={4}
      accessibilityRole="button"
    >
      <Text style={[styles.smallBtnText, { color: plum ? colors.paperWhite : colors.textPrimary }]}>{label}</Text>
    </Pressable>
  );
}

/**
 * A row with no onPress renders as plain information — no chevron, no press
 * feedback. A tappable-looking row that does nothing is a dead tap (§6.3).
 */
function SettingRow({
  title,
  subtitle,
  onPress,
  trailing,
}: {
  title: string;
  subtitle: string;
  onPress?: () => void;
  trailing?: string;
}) {
  const content = (
    <>
      <View style={{ flex: 1 }}>
        <Text style={styles.settingTitle}>{title}</Text>
        <Text style={styles.settingSub} numberOfLines={2}>
          {subtitle}
        </Text>
      </View>
      {trailing ? <Text style={styles.trailing}>{trailing}</Text> : null}
      {onPress ? <MaterialIcons name="chevron-right" size={24} color={colors.textMuted} /> : null}
    </>
  );
  return (
    <View>
      {onPress ? (
        <Pressable
          style={({ pressed }) => [styles.settingRow, pressed && styles.pressed]}
          onPress={onPress}
          accessibilityRole="button"
        >
          {content}
        </Pressable>
      ) : (
        <View style={styles.settingRow}>{content}</View>
      )}
      <View style={styles.divider} />
    </View>
  );
}

function bibOrRef(r: MarathonEntitlement): string {
  return r.bibNumber ? `Bib #${r.bibNumber}` : `Ref ${r.registrationRef}`;
}

function tierLabel(r: MarathonEntitlement): string {
  return r.tier === 'PREMIUM' ? 'Premium VIP' : 'Classic';
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: 'flex-end' },
  // M3 ModalBottomSheet scrim: 32% black.
  scrim: { ...StyleSheet.absoluteFill, backgroundColor: 'rgba(0,0,0,0.32)' },
  sheet: {
    maxHeight: '92%',
    backgroundColor: colors.paperWhite,
    borderTopLeftRadius: radii.sheet,
    borderTopRightRadius: radii.sheet,
  },
  // Drag handle: 36×4, RCS 2, BorderRule, padding vertical 8.
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: 2,
    marginVertical: 8,
    backgroundColor: colors.borderRule,
  },
  // Content padding H20, bottom 24 (+ navigation bar).
  body: { paddingHorizontal: 20 },
  pressed: { opacity: 0.7 },

  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  headerId: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 14 },
  avatar: {
    width: 54,
    height: 54,
    borderRadius: 27,
    backgroundColor: colors.plumDeep,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarInitial: { fontFamily: FONTS.serif, fontSize: 22, fontWeight: '700', color: colors.paperWhite },
  name: { fontFamily: FONTS.serif, fontSize: 20, lineHeight: 26, fontWeight: '700', color: colors.textPrimary },
  contact: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted, marginTop: 2 },
  iconBtn: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center', marginRight: -12 },

  completion: { gap: 6, marginBottom: 18 },
  completionText: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },
  barTrack: { height: 4, borderRadius: 2, backgroundColor: colors.surfaceSand, overflow: 'hidden' },
  barFill: { height: 4, borderRadius: 2, backgroundColor: colors.coralBrand },

  sectionLabel: {
    fontFamily: FONTS.sans,
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.8,
    color: colors.textMuted,
    marginBottom: 6,
  },

  // Product Card: R14, CanvasBg, outlined (borderSubtle), padding 14, marginV 4.
  productCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
    backgroundColor: colors.canvasBg,
    borderRadius: radii.option,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    padding: 14,
    marginVertical: 4,
  },
  productId: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 12 },
  tile: { width: 38, height: 38, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  tileEmoji: { fontSize: 18 },
  productTitle: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.textPrimary },
  productSub: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },
  rightGroup: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  smallBtn: { height: 34, borderRadius: radii.sm, alignItems: 'center', justifyContent: 'center' },
  smallBtnText: { fontFamily: FONTS.sans, fontSize: 11, fontWeight: '700' },
  dietNote: {
    fontFamily: FONTS.sans,
    fontSize: 10.5,
    lineHeight: 14,
    color: colors.textMuted,
    marginTop: 4,
    marginBottom: 16,
  },

  // Settings rows sit on the sheet: padV 10, divider 0.6dp BorderRule.
  settingRow: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 10 },
  settingTitle: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '600', color: colors.textPrimary },
  settingSub: { fontFamily: FONTS.sans, fontSize: 11, lineHeight: 15, color: colors.textMuted },
  trailing: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.textSecondary },
  divider: { height: 0.6, backgroundColor: colors.borderRule },
  lockNote: { flexDirection: 'row', alignItems: 'center', gap: 3, marginTop: 2 },
  lockText: { fontFamily: FONTS.sans, fontSize: 10.5, color: colors.textMuted },

  // OutlinedButton h48, R12: "Log Out of TimesHealth+" CrimsonAlert 13 Bold.
  logout: {
    height: 48,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.crimsonAlert },
  deleteBtn: { alignSelf: 'center', paddingVertical: 12, marginTop: 4 },
  deleteText: { fontFamily: FONTS.sans, fontSize: 11.5, color: colors.textMuted },

  // ── Edit ──
  editHint: { fontFamily: FONTS.sans, fontSize: 12, lineHeight: 17, color: colors.textSecondary, marginBottom: 14 },
  input: {
    height: 52,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    paddingHorizontal: 14,
    fontFamily: FONTS.sans,
    fontSize: 15,
    color: colors.textPrimary,
  },
  inputError: { borderColor: colors.crimsonAlert },
  dobRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 8 },
  dobLabel: { fontFamily: FONTS.sans, fontSize: 10, fontWeight: '800', letterSpacing: 0.8, color: colors.textMuted },
  dobInput: { textAlign: 'center', letterSpacing: 1 },
  dobSlash: { fontFamily: FONTS.sans, fontSize: 18, color: colors.textMuted, paddingBottom: 14 },
  genderGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  option: {
    flexGrow: 1,
    flexBasis: '45%',
    height: 46,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    alignItems: 'center',
    justifyContent: 'center',
  },
  optionOn: { backgroundColor: colors.plumTint, borderColor: colors.plumBrand },
  optionText: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '500', color: colors.textPrimary },
  optionTextOn: { color: colors.plumDeep, fontWeight: '700' },
  editError: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 16,
    color: colors.crimsonAlert,
    marginTop: 8,
  },
  saveBtn: {
    height: 48,
    borderRadius: radii.md,
    backgroundColor: colors.plumBrand,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 18,
  },
  saveText: { fontFamily: FONTS.sans, fontSize: 13.5, fontWeight: '700', color: colors.paperWhite },

  // ── Help ──
  supportCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    backgroundColor: colors.canvasBg,
    borderRadius: radii.option,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
    padding: 14,
  },
  faq: {
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.borderRule,
    padding: 14,
    marginVertical: 3,
  },
  faqHead: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  faqQ: { flex: 1, fontFamily: FONTS.sans, fontSize: 13, fontWeight: '600', color: colors.textPrimary },
  faqA: { fontFamily: FONTS.sans, fontSize: 12, lineHeight: 17, color: colors.textSecondary, marginTop: 8 },
});
