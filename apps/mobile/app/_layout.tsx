import { useEffect, useRef } from 'react';
import { AppState } from 'react-native';
import { Stack, useRouter, useSegments } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { QueryClientProvider } from '@tanstack/react-query';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { queryClient } from '@/api/queryClient';
import { useSessionStore } from '@/store/session';
import { colors } from '@/theme';

/** Back after this long, and anything entitlement-shaped is re-read. */
const STALE_AFTER_BACKGROUND_MS = 60_000;

export default function RootLayout() {
  const restore = useSessionStore((s) => s.restore);
  const backgroundedAt = useRef<number | null>(null);

  useEffect(() => {
    void restore();
  }, [restore]);

  // A subscription can lapse — or a race result publish — while the app sits
  // in the background. Tab screens stay mounted, so without this the app would
  // keep showing the old state until a cold start (§3: every screen resolves
  // against the CURRENT entitlement).
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'background') {
        backgroundedAt.current = Date.now();
        return;
      }
      if (state !== 'active' || backgroundedAt.current === null) return;
      const away = Date.now() - backgroundedAt.current;
      backgroundedAt.current = null;
      if (away < STALE_AFTER_BACKGROUND_MS) return;
      void queryClient.invalidateQueries({ queryKey: ['session'] });
      void queryClient.invalidateQueries({ queryKey: ['home'] });
      void queryClient.invalidateQueries({ queryKey: ['yoga'] });
      void queryClient.invalidateQueries({ queryKey: ['marathon'] });
      void queryClient.invalidateQueries({ queryKey: ['notifications'] });
    });
    return () => sub.remove();
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        <StatusBar style="dark" />
        <Stack
          screenOptions={{
            headerShown: false,
            contentStyle: { backgroundColor: colors.canvasBg },
            animation: 'slide_from_right',
          }}
        >
          <Stack.Screen name="index" />
          <Stack.Screen name="login" options={{ animation: 'fade' }} />
          <Stack.Screen name="onboarding" />
          <Stack.Screen name="(tabs)" options={{ animation: 'fade' }} />
          <Stack.Screen name="run-tracker" options={{ animation: 'slide_from_bottom' }} />
          <Stack.Screen name="bib/[eventId]" options={{ animation: 'slide_from_bottom' }} />
          <Stack.Screen
            name="video/[id]"
            // The player's own canvas (VideoPlayerScreenKt), so the fade has no tint shift.
            options={{ animation: 'fade', contentStyle: { backgroundColor: '#150A1B' } }}
          />
          {/* Instructor reels play in-app (§6.3: "plays inline"), full screen. */}
          <Stack.Screen
            name="reel"
            options={{ animation: 'fade', contentStyle: { backgroundColor: colors.carbon950 } }}
          />
          {/* Design: the paywall is a bottom SHEET over the current screen. */}
          <Stack.Screen
            name="paywall"
            options={{
              presentation: 'transparentModal',
              animation: 'slide_from_bottom',
              contentStyle: { backgroundColor: 'transparent' },
            }}
          />
        </Stack>
        <SignedOutRedirect />
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}

/**
 * A token rejected anywhere — revoked, or the account deleted on another
 * device — flips the session store to signed-out (store/session.ts). Whatever
 * screen is showing, the user lands on login, not on tabs that can only keep
 * failing with 401s.
 */
function SignedOutRedirect() {
  const status = useSessionStore((s) => s.status);
  const segments = useSegments() as string[];
  const router = useRouter();
  // The gate (index) and login handle signed-out themselves.
  const onPublicScreen = segments.length === 0 || segments[0] === 'login';

  useEffect(() => {
    if (status !== 'signed-out' || onPublicScreen) return;
    if (router.canDismiss()) router.dismissAll();
    router.replace('/login');
  }, [status, onPublicScreen, router]);

  return null;
}
