import { useEffect } from 'react';
import { Tabs, useRouter } from 'expo-router';
import * as Notifications from 'expo-notifications';
import { useQueryClient } from '@tanstack/react-query';
import {
  syncPushRegistration,
  watchNotificationTaps,
  watchTokenRotation,
} from '@/lib/notifications';
import { MaterialCommunityIcons, MaterialIcons } from '@expo/vector-icons';
import { StyleSheet, Text, View, type ColorValue } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { TopHeader } from '@/components/TopHeader';
import { colors, layout } from '@/theme';

/**
 * PRD §4: four bottom tabs, fixed, identical for every user. Content inside
 * changes by state. Nothing is ever shown locked or greyed — a free user sees
 * a different page, not a disabled one, so there are no conditional tabs here.
 *
 * Profile is deliberately not a tab: it opens from the avatar in the shared
 * TopHeader, which — as in the design (MainActivityKt puts TopHeader in the
 * Scaffold top bar) — sits above all four tabs together with the bell.
 *
 * Visuals follow the design's BottomNavBar.kt exactly: Material symbols at
 * 24dp, 10sp labels (bold when selected), each tab lighting up in ITS OWN
 * brand colour — Yoga sage, Marathon coral, Diet gold, Home ink — with a 4dp
 * dot under the active label, on a flat 64dp paper-white bar.
 */

const TABS = [
  {
    name: 'index',
    label: 'Home',
    activeColor: colors.textPrimary,
    // The one glyph whose filled/outlined forms differ visibly.
    icon: (color: ColorValue, focused: boolean) =>
      focused ? (
        <MaterialIcons name="home" size={24} color={color} />
      ) : (
        <MaterialCommunityIcons name="home-outline" size={24} color={color} />
      ),
  },
  {
    name: 'yoga',
    label: 'Yoga',
    activeColor: colors.sageBrand,
    icon: (color: ColorValue) => <MaterialIcons name="self-improvement" size={24} color={color} />,
  },
  {
    name: 'marathon',
    label: 'Marathon',
    activeColor: colors.coralBrand,
    icon: (color: ColorValue) => <MaterialIcons name="directions-run" size={24} color={color} />,
  },
  {
    name: 'diet',
    label: 'Diet',
    activeColor: colors.goldAccent,
    icon: (color: ColorValue) => <MaterialIcons name="restaurant" size={24} color={color} />,
  },
] as const;

export default function TabsLayout() {
  const router = useRouter();
  const queryClient = useQueryClient();
  // The app draws edge-to-edge, so the bar must sit ABOVE the system gesture
  // bar / 3-button nav. The inset differs per device; a fixed height put the
  // tab labels underneath Android's gesture handle. Design: BottomNavBar is
  // navigationBarsPadding().height(64) — exactly the inset, nothing added.
  const insets = useSafeAreaInsets();
  const bottomPad = insets.bottom;

  // Only mounted when signed in, so a notification never tries to open a
  // screen before login has resolved.
  useEffect(() => {
    void syncPushRegistration();
    const stopRotation = watchTokenRotation();
    const refreshInbox = () => void queryClient.invalidateQueries({ queryKey: ['notifications'] });
    const stopTaps = watchNotificationTaps((route) => {
      refreshInbox();
      router.push(route as never);
    });
    // A push that lands while the app is open belongs in the bell's inbox now,
    // not after the next refetch — that is what lights the TopHeader dot.
    let received: { remove: () => void } | null = null;
    try {
      received = Notifications.addNotificationReceivedListener(refreshInbox);
    } catch {
      // Push is an enhancement (§11); without it the inbox still loads on open.
    }
    return () => {
      stopRotation();
      stopTaps();
      received?.remove();
    };
  }, [router, queryClient]);

  return (
    <Tabs
      screenOptions={{
        // One brand bar for every tab; it draws its own status-bar inset.
        headerShown: true,
        header: () => <TopHeader />,
        tabBarInactiveTintColor: colors.textMuted,
        tabBarStyle: [styles.bar, { height: layout.bottomNavHeight + bottomPad, paddingBottom: bottomPad }],
        tabBarItemStyle: { paddingVertical: 6 },
      }}
    >
      {TABS.map((tab) => (
        <Tabs.Screen
          key={tab.name}
          name={tab.name}
          options={{
            title: tab.label,
            tabBarActiveTintColor: tab.activeColor,
            tabBarIcon: ({ color, focused }) => tab.icon(color, focused),
            // Custom label: the design sets the weight by state and draws a
            // 4dp dot UNDER the active label — the stock label can't do either.
            tabBarLabel: ({ color, focused }) => (
              <View style={styles.labelWrap}>
                <Text style={[styles.label, { color, fontWeight: focused ? '700' : '500' }]}>
                  {tab.label}
                </Text>
                {focused ? <View style={[styles.dot, { backgroundColor: color }]} /> : null}
              </View>
            ),
          }}
        />
      ))}
    </Tabs>
  );
}

const styles = StyleSheet.create({
  // BottomNavBar.kt: flat paperWhite, no rule, no shadow.
  bar: {
    backgroundColor: colors.paperWhite,
    borderTopWidth: 0,
    elevation: 0,
    paddingHorizontal: 8,
  },
  labelWrap: { alignItems: 'center' },
  label: { fontSize: 10, lineHeight: 12, marginTop: 3 },
  dot: { width: 4, height: 4, borderRadius: 2, marginTop: 2 },
});
