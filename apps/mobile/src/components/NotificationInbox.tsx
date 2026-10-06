import { useEffect, useMemo, useRef, useState, type ComponentProps, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Animated,
  Easing,
  FlatList,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { NotificationItem } from '@th/types';
import { useNotificationInbox } from '@/api/hooks';
import { useSeenStore } from '@/lib/inboxSeen';
import { isAppRoute } from '@/lib/links';
import { formatSessionDate, serverNow } from '@/lib/time';
import { FONTS, colors, radii, tints } from '@/theme';

/**
 * The TopHeader bell's inbox — every push this user was sent, newest first,
 * so a tap on the bell is never a dead end (§11: push is an enhancement, so
 * this is where a dismissed or missed one can still be found).
 *
 * A bottom sheet in the design's sheet language (PaywallSheetKt): white,
 * 28dp top corners, a 36×4 drag handle, a dimmed scrim. The list comes from
 * GET /notifications; while that is loading or failing the sheet stays calm —
 * a quiet line, never an error screen and never a crash.
 */

type IconName = ComponentProps<typeof MaterialIcons>['name'];

const sentMs = (item: NotificationItem) => Date.parse(item.sentAt);

/** True when the inbox holds something newer than what the user last saw. */
export function useHasUnread(items: NotificationItem[] | undefined): boolean {
  const seenAt = useSeenStore((s) => s.seenAt);
  const loaded = useSeenStore((s) => s.loaded);
  const load = useSeenStore((s) => s.load);

  useEffect(() => {
    void load();
  }, [load]);

  // Until the marker is read, show no dot rather than a dot that flickers off.
  if (!loaded || !items?.length) return false;
  return items.some((i) => {
    const t = sentMs(i);
    return Number.isFinite(t) && (seenAt === null || t > seenAt);
  });
}

// ── Sheet ──────────────────────────────────────────────────────────────────

/** Icon per kind; a kind this build doesn't know falls back to a plain bell. */
const KIND_LOOK: Record<string, { icon: IconName; tone: keyof typeof tints }> = {
  SESSION_REMINDER: { icon: 'schedule', tone: 'PLUM' },
  SESSION_LIVE: { icon: 'self-improvement', tone: 'EMERALD' },
  RACE_COUNTDOWN: { icon: 'directions-run', tone: 'CORAL' },
  RACE_DAY_INFO: { icon: 'directions-run', tone: 'CORAL' },
  RESULT_PUBLISHED: { icon: 'emoji-events', tone: 'GOLD' },
};
const DEFAULT_LOOK = { icon: 'notifications-none' as IconName, tone: 'NEUTRAL' as const };

/** "Just now", "12m ago", "2h ago", "Yesterday", then the full date. */
function relativeTime(iso: string): string {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return '';
  const mins = Math.floor(Math.max(0, serverNow() - t) / 60_000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  if (hours < 48) return 'Yesterday';
  return formatSessionDate(iso);
}

export function NotificationInbox({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { data, isLoading, isError, refetch } = useNotificationInbox();
  const markSeen = useSeenStore((s) => s.markSeen);
  // What was already seen when the sheet opened, so the rows that were new
  // keep their dot while the user is looking at them.
  const [seenOnOpen, setSeenOnOpen] = useState<number | null>(null);

  const items = useMemo(
    () =>
      [...(data?.items ?? [])].sort((a, b) => (sentMs(b) || 0) - (sentMs(a) || 0)),
    [data?.items],
  );

  useEffect(() => {
    if (!visible) return;
    setSeenOnOpen(useSeenStore.getState().seenAt);
    // The bell is where people look for "did I miss something?" — show now, not a minute ago.
    void refetch();
  }, [visible, refetch]);

  // Opening the sheet marks everything in it as seen.
  useEffect(() => {
    if (!visible) return;
    const newest = items[0] ? sentMs(items[0]) : NaN;
    if (Number.isFinite(newest)) markSeen(newest);
  }, [visible, items, markSeen]);

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
  const translateY = rise.interpolate({ inputRange: [0, 1], outputRange: [480, 0] });

  const open = (item: NotificationItem) => {
    // Only in-app routes; anything else is shown but not navigable.
    if (!isAppRoute(item.route)) return;
    onClose();
    router.push(item.route as never);
  };

  let body: ReactNode;
  if (items.length > 0) {
    body = (
      <FlatList
        data={items}
        keyExtractor={(i) => i.id}
        style={styles.list}
        contentContainerStyle={styles.listContent}
        renderItem={({ item }) => (
          <Row
            item={item}
            unread={seenOnOpen === null || sentMs(item) > seenOnOpen}
            onPress={() => open(item)}
          />
        )}
      />
    );
  } else if (isLoading) {
    body = (
      <View style={styles.state}>
        <ActivityIndicator color={colors.coralBrand} />
      </View>
    );
  } else if (isError) {
    body = (
      <View style={styles.state}>
        <Text style={styles.stateBody}>Notifications couldn&rsquo;t load right now.</Text>
        <Pressable onPress={() => void refetch()} hitSlop={10} accessibilityRole="button">
          <Text style={styles.retry}>Try again</Text>
        </Pressable>
      </View>
    );
  } else {
    body = (
      <View style={styles.state}>
        <View style={styles.emptyIcon}>
          <MaterialIcons name="notifications-none" size={24} color={colors.textMuted} />
        </View>
        <Text style={styles.stateTitle}>You&rsquo;re all caught up</Text>
        <Text style={styles.stateBody}>Class reminders and race updates will appear here.</Text>
      </View>
    );
  }

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onClose}
    >
      <View style={styles.root}>
        <Pressable
          style={styles.scrim}
          onPress={onClose}
          accessibilityRole="button"
          accessibilityLabel="Close notifications"
        />
        <Animated.View
          style={[styles.sheet, { paddingBottom: insets.bottom + 12, transform: [{ translateY }] }]}
        >
          <View style={styles.handle} />
          <View style={styles.header}>
            <Text style={styles.title} accessibilityRole="header">
              Notifications
            </Text>
            <Pressable onPress={onClose} hitSlop={10} accessibilityRole="button" accessibilityLabel="Close">
              <MaterialIcons name="close" size={22} color={colors.textMuted} />
            </Pressable>
          </View>
          {body}
        </Animated.View>
      </View>
    </Modal>
  );
}

function Row({
  item,
  unread,
  onPress,
}: {
  item: NotificationItem;
  unread: boolean;
  onPress: () => void;
}) {
  const look = KIND_LOOK[item.kind] ?? DEFAULT_LOOK;
  const t = tints[look.tone];
  const navigable = isAppRoute(item.route);
  return (
    <Pressable
      onPress={onPress}
      disabled={!navigable}
      style={({ pressed }) => [styles.row, pressed && navigable && styles.rowPressed]}
      accessibilityRole={navigable ? 'button' : 'text'}
      accessibilityLabel={`${unread ? 'New. ' : ''}${item.title}. ${item.body}`}
    >
      <View style={[styles.rowIcon, { backgroundColor: t.bg }]}>
        <MaterialIcons name={look.icon} size={20} color={t.fg} />
      </View>
      <View style={styles.rowText}>
        <Text style={styles.rowTitle} numberOfLines={2}>
          {item.title}
        </Text>
        {item.body ? (
          <Text style={styles.rowBody} numberOfLines={3}>
            {item.body}
          </Text>
        ) : null}
        <Text style={styles.rowTime}>{relativeTime(item.sentAt)}</Text>
      </View>
      {unread ? <View style={styles.unreadDot} /> : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: 'flex-end' },
  // M3 ModalBottomSheet scrim: 32% black.
  scrim: { ...StyleSheet.absoluteFill, backgroundColor: 'rgba(0,0,0,0.32)' },
  sheet: {
    maxHeight: '80%',
    backgroundColor: colors.paperWhite,
    borderTopLeftRadius: radii.sheet,
    borderTopRightRadius: radii.sheet,
  },
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: 2,
    marginVertical: 10,
    backgroundColor: colors.borderRule,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingBottom: 8,
  },
  title: {
    fontFamily: FONTS.serif,
    fontSize: 20,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  list: { flexGrow: 0 },
  listContent: { paddingHorizontal: 20, paddingBottom: 8 },
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 12,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.borderRule,
  },
  rowPressed: { opacity: 0.6 },
  rowIcon: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  rowText: { flex: 1, gap: 2 },
  rowTitle: {
    fontFamily: FONTS.sans,
    fontSize: 13.5,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  rowBody: {
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
  },
  rowTime: {
    marginTop: 2,
    fontFamily: FONTS.sans,
    fontSize: 10.5,
    color: colors.textMuted,
  },
  unreadDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginTop: 6,
    backgroundColor: colors.coralBrand,
  },
  state: {
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 32,
    paddingTop: 24,
    paddingBottom: 32,
  },
  emptyIcon: {
    width: 48,
    height: 48,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 4,
    backgroundColor: colors.surfaceSand,
  },
  stateTitle: {
    fontFamily: FONTS.serif,
    fontSize: 18,
    fontWeight: '600',
    color: colors.textPrimary,
  },
  stateBody: {
    fontFamily: FONTS.sans,
    fontSize: 12.5,
    lineHeight: 18,
    textAlign: 'center',
    color: colors.textMuted,
  },
  retry: {
    marginTop: 4,
    fontFamily: FONTS.sans,
    fontSize: 12,
    fontWeight: '700',
    color: colors.coralBrand,
  },
});
