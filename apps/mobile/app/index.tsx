import { useEffect, useState } from 'react';
import { ActivityIndicator, Linking, Pressable, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Constants from 'expo-constants';
import { Redirect, useRouter } from 'expo-router';
import { useAppConfig, useSession } from '@/api/hooks';
import { listOfflinePasses, type OfflinePass } from '@/lib/offlinePass';
import { useSessionStore } from '@/store/session';
import { colors, radii, spacing, type } from '@/theme';

/**
 * Entry gate — PRD §5, plus the two launch switches every release needs.
 *
 *   launch → [force-update? maintenance?] → pre-login carousel → login
 *          ├─ known account (yoga sub, marathon reg, or both) → HOME
 *          └─ new / free account → onboarding → HOME
 *
 * The launch switches come from the PUBLIC /config endpoint and are checked
 * before sign-in. The update gate is the one thing that cannot be added
 * later: it has to already be inside the version you need people to leave —
 * including a version whose login is what broke.
 *
 * "Known account skips onboarding" is resolved server-side (needsOnboarding),
 * so a returning subscriber never sees onboarding again, even on a fresh install.
 */

const APP_VERSION = Constants.expoConfig?.version ?? '0.0.0';
const PACKAGE = Constants.expoConfig?.android?.package ?? '';

/** True when `current` is older than `minimum` (numeric major.minor.patch). */
function isOlder(current: string, minimum: string): boolean {
  const a = current.split('.').map((n) => Number.parseInt(n, 10) || 0);
  const b = minimum.split('.').map((n) => Number.parseInt(n, 10) || 0);
  for (let i = 0; i < 3; i += 1) {
    const x = a[i] ?? 0;
    const y = b[i] ?? 0;
    if (x !== y) return x < y;
  }
  return false;
}

export default function Gate() {
  const config = useAppConfig();
  const status = useSessionStore((s) => s.status);
  const signedIn = status === 'signed-in';
  // Wait for auth to settle before asking the server who this is.
  const session = useSession(signedIn);
  // Race morning at a packed stadium is when the API is least reachable, so a
  // saved race pass must open from here without waiting for it (§8.3).
  const passes = useSavedPasses();

  // Fail OPEN on the launch config: a hiccup fetching it must never lock every
  // user out. If the API is truly down, the session step says so below.
  if (config.isLoading) return <Splash passes={passes} />;
  if (config.data && isOlder(APP_VERSION, config.data.minSupportedAppVersion)) {
    return <UpdateRequired />;
  }
  if (config.data?.maintenance.active) {
    return (
      <Message
        title="Back shortly"
        body={config.data.maintenance.message ?? 'TimesHealth+ is being updated. Please try again soon.'}
        onRetry={() => void config.refetch()}
      />
    );
  }

  if (status === 'loading') return <Splash passes={passes} />;
  if (!signedIn) return <Redirect href="/login" />;
  if (session.isLoading) return <Splash passes={passes} />;

  if (session.isError || !session.data) {
    return (
      <Message
        title="We couldn’t reach TimesHealth+"
        body="Check your connection and try again."
        onRetry={() => void session.refetch()}
        retrying={session.isFetching}
      >
        <PassShortcuts passes={passes} />
      </Message>
    );
  }

  return <Redirect href={session.data.needsOnboarding ? '/onboarding' : '/(tabs)'} />;
}

function UpdateRequired() {
  const openStore = async () => {
    try {
      await Linking.openURL(`market://details?id=${PACKAGE}`);
    } catch {
      await Linking.openURL(`https://play.google.com/store/apps/details?id=${PACKAGE}`);
    }
  };
  return (
    <View style={styles.wrap}>
      <Text style={[type.displaySmall, styles.brand]}>TimesHealth+</Text>
      <Text style={[type.headlineSmall, styles.center]}>Please update the app</Text>
      <Text style={[type.bodyMedium, styles.center, styles.muted]}>
        This version is no longer supported. Update to keep joining classes and using your race
        pass.
      </Text>
      <Pressable style={styles.button} onPress={() => void openStore()}>
        <Text style={[type.titleMedium, { color: colors.paperWhite }]}>Update now</Text>
      </Pressable>
      <Text style={[type.bodySmall, styles.muted]}>Version {APP_VERSION}</Text>
    </View>
  );
}

function Message({
  title,
  body,
  onRetry,
  retrying,
  children,
}: {
  title: string;
  body: string;
  onRetry: () => void;
  retrying?: boolean;
  children?: React.ReactNode;
}) {
  return (
    <View style={styles.wrap}>
      <Text style={[type.headlineSmall, styles.center]}>{title}</Text>
      <Text style={[type.bodyMedium, styles.center, styles.muted]}>{body}</Text>
      <Pressable style={styles.button} onPress={onRetry} disabled={retrying}>
        {retrying ? (
          <ActivityIndicator color={colors.paperWhite} />
        ) : (
          <Text style={[type.titleMedium, { color: colors.paperWhite }]}>Try again</Text>
        )}
      </Pressable>
      {children}
    </View>
  );
}

function Splash({ passes }: { passes: OfflinePass[] }) {
  // A normal launch is gone in a second. Still here after a few, the network
  // is struggling — offer the saved pass rather than make a runner wait.
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const id = setTimeout(() => setSlow(true), 3000);
    return () => clearTimeout(id);
  }, []);

  return (
    <View style={styles.wrap}>
      <Text style={[type.displaySmall, styles.brand]}>TimesHealth+</Text>
      <Text style={[type.bodyMedium, styles.center]}>One login across Yoga, Marathon & Diet.</Text>
      <ActivityIndicator color={colors.coralBrand} style={{ marginTop: 24 }} />
      {slow ? <PassShortcuts passes={passes} /> : null}
    </View>
  );
}

function useSavedPasses(): OfflinePass[] {
  const [passes, setPasses] = useState<OfflinePass[]>([]);
  useEffect(() => {
    let cancelled = false;
    void listOfflinePasses()
      .then((p) => {
        if (!cancelled) setPasses(p);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);
  return passes;
}

function PassShortcuts({ passes }: { passes: OfflinePass[] }) {
  const router = useRouter();
  if (!passes.length) return null;
  return (
    <View style={styles.passes}>
      <Text style={[type.bodySmall, styles.center, styles.muted]}>
        No signal? Your race pass is saved on this phone.
      </Text>
      {passes.map((p) => (
        <Pressable
          key={p.eventId}
          style={styles.passButton}
          onPress={() => router.push({ pathname: '/bib/[eventId]', params: { eventId: p.eventId } })}
        >
          <Ionicons name="qr-code" size={18} color={colors.textPrimary} />
          <Text style={[type.titleSmall, { flexShrink: 1 }]} numberOfLines={1}>
            Open race pass · {p.eventName}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.canvasBg,
    paddingHorizontal: 32,
    gap: 8,
  },
  brand: { color: colors.coralBrand, marginBottom: 4 },
  center: { textAlign: 'center' },
  muted: { color: colors.textMuted },
  button: {
    marginTop: spacing['5xl'],
    backgroundColor: colors.coralBrand,
    borderRadius: radii.md,
    paddingHorizontal: spacing['8xl'],
    paddingVertical: spacing['4xl'],
    minWidth: 180,
    alignItems: 'center',
  },
  passes: { marginTop: spacing['7xl'], gap: spacing.lg, alignSelf: 'stretch', alignItems: 'center' },
  passButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.lg,
    backgroundColor: colors.paperWhite,
    borderWidth: 1,
    borderColor: colors.borderRule,
    borderRadius: radii.md,
    paddingHorizontal: spacing['5xl'],
    paddingVertical: spacing['3xl'],
    maxWidth: '100%',
  },
});
