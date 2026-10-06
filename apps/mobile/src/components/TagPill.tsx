import { StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { FONTS, colors, radii, tints } from '@/theme';

/**
 * The design's one status chip — CommonComponentsKt.TagPill:
 * 6dp corners, padding 7×3, 4dp gap, 9sp ExtraBold upper-case text at +0.6
 * letter-spacing. LIVE is solid emerald with a 5dp white dot.
 *
 * Used for hero status ("LIVE NOW", "MEMBERSHIP EXPIRED"), FREE / REEL /
 * category labels, REGISTER / EXPLORE actions, "SAVE 58%", "STEP 2 OF 4"…
 */
export type TagPillTone = 'CORAL' | 'SAGE' | 'EMERALD' | 'GOLD' | 'NEUTRAL' | 'PLUM' | 'LIVE';

export function TagPill({
  label,
  tone = 'CORAL',
  icon,
  style,
}: {
  label: string;
  tone?: TagPillTone;
  icon?: keyof typeof Ionicons.glyphMap;
  style?: StyleProp<ViewStyle>;
}) {
  const t = tints[tone];
  return (
    <View style={[styles.pill, { backgroundColor: t.bg }, style]}>
      {tone === 'LIVE' ? <View style={styles.liveDot} /> : null}
      {icon ? <Ionicons name={icon} size={10} color={t.fg} /> : null}
      <Text style={[styles.label, { color: t.fg }]} numberOfLines={1}>
        {label.toUpperCase()}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    gap: 4,
    borderRadius: radii.tag,
    paddingHorizontal: 7,
    paddingVertical: 3,
  },
  label: {
    fontFamily: FONTS.sans,
    fontSize: 9,
    lineHeight: 12,
    fontWeight: '800',
    letterSpacing: 0.6,
  },
  liveDot: { width: 5, height: 5, borderRadius: 2.5, backgroundColor: colors.paperWhite },
});
