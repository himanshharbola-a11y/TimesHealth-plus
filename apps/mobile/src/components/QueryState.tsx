import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { ApiRequestError } from '@/api/client';
import { colors, radii, spacing, type } from '@/theme';

/**
 * Loading and error states shared by every data-driven screen.
 *
 * Screens used to treat "no data yet" as "still loading", so a failed request
 * spun forever with no way out. Every screen now distinguishes the two, and an
 * error always offers a retry.
 */

export function LoadingState({ dark = false }: { dark?: boolean }) {
  return (
    <View style={[styles.center, dark && styles.dark]}>
      <ActivityIndicator color={dark ? colors.paperWhite : colors.coralBrand} />
    </View>
  );
}

function messageFor(error: unknown): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 0) return error.body.message; // offline / timeout — already user-facing
    if (error.status === 404) return 'This isn’t available any more.';
    if (error.status === 429) return 'Too many requests just now. Wait a moment and try again.';
    if (error.status >= 500) return 'Something went wrong on our side. Please try again.';
  }
  return 'We couldn’t load this. Check your connection and try again.';
}

export function ErrorState({
  error,
  message,
  onRetry,
  onBack,
  dark = false,
}: {
  error?: unknown;
  /** Overrides the message derived from `error`. */
  message?: string;
  onRetry?: () => void;
  /** For full-screen routes without a header: never a screen with no way out. */
  onBack?: () => void;
  dark?: boolean;
}) {
  return (
    <View style={[styles.center, dark && styles.dark]}>
      <Ionicons name="cloud-offline-outline" size={36} color={dark ? colors.textOnDarkMuted : colors.textMuted} />
      <Text style={[type.bodyLarge, styles.text, dark && { color: colors.textOnDarkMuted }]}>
        {message ?? messageFor(error)}
      </Text>
      {onRetry ? (
        <Pressable style={styles.button} onPress={onRetry} accessibilityRole="button">
          <Text style={[type.titleSmall, { color: colors.paperWhite }]}>Try again</Text>
        </Pressable>
      ) : null}
      {onBack ? (
        <Pressable onPress={onBack} hitSlop={8} accessibilityRole="button" style={{ marginTop: 14 }}>
          <Text style={[type.titleSmall, { color: dark ? colors.textOnDarkMuted : colors.textSecondary }]}>
            Go back
          </Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing['7xl'],
    gap: spacing.xl,
    backgroundColor: colors.canvasBg,
  },
  dark: { backgroundColor: colors.carbon950 },
  text: { textAlign: 'center', color: colors.textSecondary },
  button: {
    backgroundColor: colors.coralBrand,
    borderRadius: radii.md,
    paddingHorizontal: spacing['7xl'],
    paddingVertical: spacing.xl,
  },
});
