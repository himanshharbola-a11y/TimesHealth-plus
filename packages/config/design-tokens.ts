/**
 * TimesHealth+ design tokens.
 *
 * Extracted verbatim from the design prototype (decompiled Compose source,
 * see assets/design-reference/). These are not approximations — every value
 * here is the literal from ui/theme/ColorKt.java and ui/theme/TypeKt.java.
 *
 * Do not invent new values. If a screen needs a colour or size that is not
 * here, check the decompiled source for that screen first.
 */

export const colors = {
  // Dark surfaces — used for hero banners, the bib, and live states.
  carbon950: '#121417',
  carbon900: '#1A1D21',
  carbon800: '#242830',
  carbon700: '#323742',

  // App canvas. Warm off-white, not pure grey.
  canvasBg: '#FAF7F3',
  canvasBgSecondary: '#F3ECE3',
  paperWhite: '#FFFFFF',
  surfaceSand: '#F1EBE3',

  borderRule: '#E6DFD6',
  /** 10% black. Use rgba, not a flat hex, so it composites over imagery. */
  borderSubtle: 'rgba(0,0,0,0.10)',

  // Coral — primary brand / marathon.
  coralBrand: '#E8533A',
  coralBright: '#FF5733',
  coralDark: '#C73C26',
  coralTint: '#FFF0EC',
  coralBorder: '#FFD5CC',

  // Plum — yoga / premium.
  plumDeep: '#3B0E4A',
  plumBrand: '#6B2E8F',
  plumTint: '#F3EAF8',
  plumLine: '#E3D3EE',

  // Sage — diet / wellness.
  sageBrand: '#1B4931',
  sageSecondary: '#1D9E75',
  sageTint: '#EBF5F0',
  sageLine: '#CCE8D9',

  /** Reserved for the live-session state (§6.1 "visually distinct live state"). */
  liveEmerald: '#10B981',
  liveEmeraldTint: '#E8F8F2',

  goldAccent: '#C98A16',
  goldTint: '#FCF3E2',

  amberWarn: '#F59E0B',
  crimsonAlert: '#EF4444',

  textPrimary: '#1A1418',
  textSecondary: '#514A57',
  textMuted: '#8A8390',
  textOnDark: '#FFFFFF',
  textOnDarkMuted: '#B5AFB9',
} as const;

/**
 * Category hero banner gradients, keyed by YogaCategory.bannerTheme.
 * Horizontal, left → right. Taken from CategoryHeroBanner in the decompiled
 * YogaSessionScreen: 165dp tall, 18dp horizontal inset, 18dp corner radius.
 */
export const categoryGradients = {
  PLUM: ['#2C1338', '#4A1A59'],
  CORAL: ['#8A1E14', '#C7432B'],
  AMBER: ['#7A4A0A', '#B87820'],
  OCEAN: ['#0F3554', '#1E5B8C'],
  EMERALD: ['#0C4A31', '#1B7A53'],
  SAGE: ['#2E453B', '#4A6B5D'],
} as const;

export const categoryBanner = {
  height: 165,
  insetX: 18,
  radius: 18,
} as const;

/** Soft tint variants, for pills and badges rather than hero banners. */
export const tints = {
  // TagPill pairs from CommonComponentsKt.TagPill — fg is the design's text colour.
  CORAL: { bg: colors.coralTint, line: colors.coralBorder, fg: colors.coralBrand },
  PLUM: { bg: colors.plumTint, line: colors.plumLine, fg: colors.plumBrand },
  SAGE: { bg: colors.sageTint, line: colors.sageLine, fg: colors.sageBrand },
  AMBER: { bg: colors.goldTint, line: colors.goldAccent, fg: colors.goldAccent },
  EMERALD: { bg: colors.liveEmeraldTint, line: colors.sageLine, fg: colors.liveEmerald },
  OCEAN: { bg: colors.canvasBgSecondary, line: colors.borderRule, fg: colors.carbon800 },
  NEUTRAL: { bg: colors.surfaceSand, line: colors.borderRule, fg: colors.textSecondary },
  GOLD: { bg: colors.goldTint, line: colors.goldAccent, fg: colors.goldAccent },
  LIVE: { bg: colors.liveEmerald, line: colors.liveEmerald, fg: colors.paperWhite },
} as const;

/**
 * Type scale. The prototype pairs a SERIF display face with a sans body face —
 * that contrast is the single most distinctive thing about the design, so the
 * serif must be a real font choice, not the platform default.
 *
 * `letterSpacing` is in px (RN) converted from Compose sp.
 */
export const typography = {
  displayLarge:  { family: 'serif', size: 40, lineHeight: 44, weight: '500', letterSpacing: -0.5 },
  displayMedium: { family: 'serif', size: 32, lineHeight: 36, weight: '500', letterSpacing: -0.5 },
  displaySmall:  { family: 'serif', size: 26, lineHeight: 30, weight: '500', letterSpacing: -0.3 },
  headlineLarge: { family: 'serif', size: 24, lineHeight: 28, weight: '600', letterSpacing: -0.2 },
  headlineSmall: { family: 'serif', size: 20, lineHeight: 25, weight: '500', letterSpacing: -0.2 },

  titleLarge:  { family: 'sans', size: 17, lineHeight: 22, weight: '700', letterSpacing: -0.1 },
  titleMedium: { family: 'sans', size: 16, lineHeight: 21, weight: '700', letterSpacing: 0 },
  titleSmall:  { family: 'sans', size: 14, lineHeight: 18, weight: '600', letterSpacing: 0 },
  titleTiny:   { family: 'sans', size: 12, lineHeight: 16, weight: '700', letterSpacing: 0 },

  bodyLarge:  { family: 'sans', size: 15, lineHeight: 22, weight: '400', letterSpacing: 0 },
  bodyMedium: { family: 'sans', size: 13, lineHeight: 19, weight: '400', letterSpacing: 0 },
  bodySmall:  { family: 'sans', size: 11, lineHeight: 15, weight: '400', letterSpacing: 0 },

  /** All-caps eyebrow labels — "MEMBER ENTITLEMENT", "OFFICIAL FINISH TIME". */
  labelLarge: { family: 'sans', size: 13, lineHeight: 17, weight: '700', letterSpacing: 0.2 },
  labelSmall: { family: 'sans', size: 11, lineHeight: 14, weight: '700', letterSpacing: 0.4 },
  /** The design's M3 labelSmall (TypeKt): 9.5sp bold, +0.8 — section eyebrows. */
  labelTiny: { family: 'sans', size: 9.5, lineHeight: 12, weight: '700', letterSpacing: 0.8 },
} as const;

/**
 * Spacing scale, ranked by frequency in the prototype. It is a 2px-based scale,
 * not an 8pt grid — 12, 18, 14 and 10 are all heavily used. Do not "tidy" these
 * to multiples of 8; the design's rhythm depends on them.
 */
export const spacing = {
  xxs: 2,
  xs: 4,
  sm: 6,
  md: 8,
  lg: 10,
  xl: 12,
  xxl: 14,
  '3xl': 16,
  '4xl': 18,
  '5xl': 20,
  '6xl': 24,
  '7xl': 28,
  '8xl': 32,
  '9xl': 48,
} as const;

export const radii = {
  /** TagPill. */
  tag: 6,
  sm: 8,
  md: 12,
  /** Promo strip, plan cards, onboarding options, form fields. */
  option: 14,
  /** Content cards (video, reel, article, quote, secondary hero). */
  lg: 16,
  /** Run tracker tile. */
  xl: 20,
  /** Hero cards. */
  hero: 22,
  xxl: 24,
  /** Bottom sheets (login panel, paywall). */
  sheet: 28,
  pill: 999,
} as const;

export const layout = {
  /** Bottom nav height from BottomNavBarKt. */
  bottomNavHeight: 64,
  /** 18dp — TopHeader, SectionHeader, hero wrapper, LazyRow padding, promo, tiles. */
  screenGutter: 18,
  /** §8.2: a free user's primary race box is ~80% of the first screen. */
  primaryRaceBoxScreenFraction: 0.8,
} as const;
