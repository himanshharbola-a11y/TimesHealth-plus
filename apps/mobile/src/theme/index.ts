/**
 * Theme — re-exports the extracted design tokens and adds React Native
 * specific helpers. Token values live in packages/config/design-tokens.ts and
 * come verbatim from the design prototype; do not redefine them here.
 */

import { Platform, type TextStyle } from 'react-native';
import {
  categoryBanner,
  categoryGradients,
  colors,
  layout,
  radii,
  spacing,
  tints,
  typography,
} from '@th/config/design-tokens';

export { colors, spacing, radii, layout, categoryGradients, categoryBanner, tints };

/**
 * The design pairs a serif display face with a sans body face. That contrast
 * is the most distinctive thing about it, so the serif must resolve to a real
 * serif on both platforms rather than falling back to the system sans.
 */
export const FONTS = {
  serif: Platform.select({ ios: 'Georgia', android: 'serif', default: 'serif' }),
  sans: Platform.select({ ios: 'System', android: 'sans-serif', default: 'System' }),
} as const;

type TypeKey = keyof typeof typography;

function build(key: TypeKey): TextStyle {
  const t = typography[key];
  return {
    fontFamily: t.family === 'serif' ? FONTS.serif : FONTS.sans,
    fontSize: t.size,
    lineHeight: t.lineHeight,
    fontWeight: t.weight as TextStyle['fontWeight'],
    letterSpacing: t.letterSpacing,
    color: colors.textPrimary,
  };
}

export const type = Object.fromEntries(
  (Object.keys(typography) as TypeKey[]).map((k) => [k, build(k)]),
) as Record<TypeKey, TextStyle>;

/** Eyebrow labels in the design are always upper-cased at render time. */
export const upper = (s: string) => s.toUpperCase();

export const shadow = {
  card: Platform.select({
    ios: {
      shadowColor: '#000',
      shadowOpacity: 0.06,
      shadowRadius: 12,
      shadowOffset: { width: 0, height: 4 },
    },
    android: { elevation: 2 },
    default: {},
  }),
  hero: Platform.select({
    ios: {
      shadowColor: '#000',
      shadowOpacity: 0.12,
      shadowRadius: 20,
      shadowOffset: { width: 0, height: 8 },
    },
    android: { elevation: 6 },
    default: {},
  }),
} as const;
