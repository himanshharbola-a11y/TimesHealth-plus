import { useEffect, useState } from 'react';
import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { firebaseEnabled } from '@/lib/firebaseAuth';
import { hasPrimed, markPrimed, requestAndRegister } from '@/lib/notifications';
import { colors, radii, spacing, type } from '@/theme';

/**
 * Our own ask, shown before the OS prompt — PRD §11.
 *
 * Shown once per install. "Not now" is a first-class answer: it closes the
 * sheet, never re-asks on this install, and changes nothing else, because
 * WhatsApp still carries every critical message.
 */
export function NotificationPrimer() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    // A build that can't receive push (no Firebase) must not spend the one
    // real OS ask on nothing — it stays unasked until a build that can.
    if (!firebaseEnabled) return;
    let cancelled = false;
    void hasPrimed().then((done) => {
      if (!done && !cancelled) setVisible(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const finish = async (accept: boolean) => {
    setVisible(false);
    await markPrimed();
    if (accept) await requestAndRegister();
  };

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={() => void finish(false)}>
      <View style={styles.scrim}>
        <View style={styles.card}>
          <View style={styles.icon}>
            <Ionicons name="notifications" size={26} color={colors.paperWhite} />
          </View>
          <Text style={[type.headlineLarge, styles.center]}>Never miss a class</Text>
          <Text style={[type.bodyLarge, styles.center, { color: colors.textSecondary }]}>
            Convenient reminders and 1-tap joining for your live batch, plus race day alerts.
          </Text>

          <View style={styles.list}>
            <Item icon="time-outline" text="A reminder before your chosen yoga slot" />
            <Item icon="flash-outline" text="A nudge the moment your class goes live" />
            <Item icon="trophy-outline" text="Race countdowns and your result, when published" />
          </View>

          <Pressable style={styles.cta} onPress={() => void finish(true)}>
            <Text style={[type.titleMedium, { color: colors.paperWhite }]}>Turn on notifications</Text>
          </Pressable>
          <Pressable style={styles.secondary} onPress={() => void finish(false)}>
            <Text style={[type.titleSmall, { color: colors.textMuted }]}>Not now</Text>
          </Pressable>
          <Text style={[type.bodySmall, styles.center, { color: colors.textMuted }]}>
            Your WhatsApp class links and race updates continue either way.
          </Text>
        </View>
      </View>
    </Modal>
  );
}

function Item({ icon, text }: { icon: keyof typeof Ionicons.glyphMap; text: string }) {
  return (
    <View style={styles.item}>
      <Ionicons name={icon} size={18} color={colors.coralBrand} />
      <Text style={[type.bodyMedium, { flex: 1, color: colors.textSecondary }]}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  scrim: {
    flex: 1,
    backgroundColor: 'rgba(18,20,23,0.6)',
    justifyContent: 'center',
    padding: spacing['6xl'],
  },
  card: {
    backgroundColor: colors.canvasBg,
    borderRadius: radii.xxl,
    padding: spacing['6xl'],
    gap: spacing.xl,
  },
  icon: {
    alignSelf: 'center',
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  center: { textAlign: 'center' },
  list: { gap: spacing.lg, marginVertical: spacing.md },
  item: { flexDirection: 'row', alignItems: 'center', gap: spacing.xl },
  cta: {
    backgroundColor: colors.coralBrand,
    borderRadius: radii.md,
    paddingVertical: spacing['4xl'],
    alignItems: 'center',
  },
  secondary: { alignItems: 'center', paddingVertical: spacing.md },
});
