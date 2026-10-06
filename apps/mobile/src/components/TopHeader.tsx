import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNotificationInbox, useSession } from '@/api/hooks';
import { FONTS, colors, layout, radii } from '@/theme';
import { NotificationInbox, useHasUnread } from './NotificationInbox';
import { ProfileDrawer } from './ProfileDrawer';

/**
 * The brand bar above every tab — design TopHeaderKt, placed in the Scaffold
 * top bar by MainActivityKt for all four tabs.
 *
 *   [♥] Times Health+          (🔔) (P)
 *       ONE HEALTH ECOSYSTEM
 *
 * The bell opens the notification inbox and wears a coral dot only while it
 * holds something unseen. The initial avatar opens Profile (PRD §10) — which
 * is why Profile is still not a tab: it is one tap away from every tab here.
 *
 * Mounted once as the Tabs `header`, so it draws its own status-bar inset.
 */
export function TopHeader() {
  const insets = useSafeAreaInsets();
  const { data: session } = useSession();
  const { data: inbox } = useNotificationInbox();
  const hasUnread = useHasUnread(inbox?.items);
  const [profileOpen, setProfileOpen] = useState(false);
  const [inboxOpen, setInboxOpen] = useState(false);

  // Design: first character of the name, upper-cased, "P" (profile) when none.
  const initial = session?.profile.name?.trim().charAt(0).toUpperCase() || 'P';

  return (
    <View style={[styles.bar, { paddingTop: insets.top + 10 }]}>
      <View style={styles.brand} accessible accessibilityRole="header" accessibilityLabel="Times Health+">
        <View style={styles.logo}>
          <MaterialIcons name="favorite" size={18} color={colors.paperWhite} />
        </View>
        <View>
          <View style={styles.wordmark}>
            <Text style={[styles.word, styles.times]}>Times</Text>
            <Text style={[styles.word, styles.health]}>Health+</Text>
          </View>
          <Text style={styles.tagline}>ONE HEALTH ECOSYSTEM</Text>
        </View>
      </View>

      <View style={styles.actions}>
        <Pressable
          onPress={() => setInboxOpen(true)}
          style={[styles.circle, styles.bell]}
          hitSlop={4}
          accessibilityRole="button"
          accessibilityLabel={hasUnread ? 'Notifications, new' : 'Notifications'}
        >
          <MaterialIcons name="notifications-none" size={20} color={colors.textSecondary} />
          {hasUnread ? <View style={styles.dot} /> : null}
        </Pressable>
        <Pressable
          onPress={() => setProfileOpen(true)}
          style={[styles.circle, styles.avatar]}
          hitSlop={4}
          accessibilityRole="button"
          accessibilityLabel="Open profile"
        >
          <Text style={styles.initial}>{initial}</Text>
        </Pressable>
      </View>

      <NotificationInbox visible={inboxOpen} onClose={() => setInboxOpen(false)} />
      <ProfileDrawer visible={profileOpen} onClose={() => setProfileOpen(false)} />
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: layout.screenGutter,
    paddingBottom: 10,
    backgroundColor: colors.canvasBg,
  },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  logo: {
    width: 32,
    height: 32,
    borderRadius: radii.sm,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  wordmark: { flexDirection: 'row', alignItems: 'baseline' },
  word: { fontFamily: FONTS.sans, fontSize: 17, letterSpacing: -0.3 },
  times: { fontWeight: '700', color: colors.textPrimary },
  health: { fontWeight: '800', color: colors.coralBrand },
  tagline: {
    fontFamily: FONTS.sans,
    fontSize: 8.5,
    fontWeight: '700',
    letterSpacing: 0.8,
    color: colors.textMuted,
  },
  actions: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  circle: {
    width: 38,
    height: 38,
    borderRadius: 19,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bell: { backgroundColor: colors.paperWhite },
  // Compose: 6dp dot aligned TopEnd, offset (-8, 8).
  dot: {
    position: 'absolute',
    top: 8,
    right: 8,
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: colors.coralBrand,
  },
  avatar: { backgroundColor: colors.carbon900 },
  initial: {
    fontFamily: FONTS.sans,
    fontSize: 14,
    fontWeight: '700',
    color: colors.paperWhite,
  },
});
