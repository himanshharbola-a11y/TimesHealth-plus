import { Linking, Pressable, Share, StyleSheet, Text, View } from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import type { ReferralState } from '@th/types';
import { TagPill } from '@/components/TagPill';
import { FONTS, colors, radii } from '@/theme';

/**
 * Refer & Win — PRD §8.3. "Surfaced as its own visible element in the tab —
 * not buried inside the detail screen." It drives registrations.
 *
 * Mechanic (the existing web one): 1 referral = lucky-draw entry for a Premium
 * upgrade; 5 referrals = guaranteed upgrade. Unique coded link, WhatsApp first.
 * Referrals count only on a completed PAID registration — the server enforces
 * that, so self-referral with disposable numbers earns nothing (docs/04 T2).
 *
 * Two looks, one behaviour (the design draws the program twice):
 *   tab    — MarathonScreenKt.ReferAndWinCard: plum gradient, white type,
 *            GOLD "REFER & WIN" pill, white "Share on WhatsApp" button.
 *   detail — RaceDetailScreenKt $lambda$28: plumTint card, CORAL "REFERRAL
 *            PROGRAM" pill, plumBrand "Share My Referral Link" button.
 */

const GOAL = 5;

export function ReferAndWinCard({
  referral,
  eventName,
  variant = 'tab',
  onOpenRace,
}: {
  referral: ReferralState;
  eventName: string;
  variant?: 'tab' | 'detail';
  /**
   * Where the earned upgrade is claimed (the race's detail screen). When given,
   * an unlocked-but-unclaimed upgrade turns the button into the way there, so
   * the reward is one tap away instead of a headline with nothing behind it.
   */
  onOpenRace?: () => void;
}) {
  const done = Math.min(GOAL, referral.confirmedReferrals);
  const entries = referral.luckyDrawEntries;
  const unlocked = referral.guaranteedUpgradeUnlocked && !referral.upgradeClaimed;
  const claimed = referral.upgradeClaimed;

  // The friend may open the link on a laptop, or type the code at checkout —
  // so the message carries both, never just one.
  const message =
    `I'm running the ${eventName} — join me! Register on TimesHealth+: ${referral.shareUrl} ` +
    `Use my code ${referral.code} when you register.`;

  // WhatsApp first (§8.3); fall back to the system share sheet when it isn't
  // installed, so the tap always does something.
  const share = async () => {
    try {
      await Linking.openURL(`whatsapp://send?text=${encodeURIComponent(message)}`);
    } catch {
      try {
        await Share.share({ message });
      } catch {
        // The user dismissed the sheet — nothing to recover.
      }
    }
  };

  const headline = claimed
    ? 'Premium upgrade claimed'
    : unlocked
      ? 'Premium upgrade unlocked!'
      : variant === 'tab'
        ? 'Get a guaranteed Premium Upgrade'
        : 'Refer friends to earn free upgrades';

  const body = claimed
    ? 'Your guaranteed upgrade is in use. Every friend who registers with your code still adds a lucky-draw entry.'
    : unlocked
      ? `${GOAL} friends registered with your code. Claim your free Premium VIP upgrade on any upcoming race where you hold a Classic entry — it's on that race's details.`
      : variant === 'tab'
        ? '1 referral enters lucky draw · 5 referrals guarantees full Premium VIP entry.'
        : '1 referral enters lucky draw · 5 referrals guarantees full Premium VIP entry. Share your link on WhatsApp in one tap.';

  const draw =
    entries > 0
      ? `${entries} lucky-draw ${entries === 1 ? 'entry' : 'entries'} so far`
      : 'Your first referral enters you in the lucky draw';

  const goClaim = unlocked && onOpenRace;
  const onPress = goClaim ? onOpenRace : () => void share();

  if (variant === 'detail') {
    return (
      <View style={styles.detailCard}>
        <View style={styles.headRow}>
          <TagPill label="Referral program" tone="CORAL" />
          <Text style={[styles.count, { color: colors.plumDeep }]}>
            {done} / {GOAL} Referred
          </Text>
        </View>
        <Text style={styles.detailTitle}>{headline}</Text>
        <Text style={styles.detailBody}>{body}</Text>
        <Text style={[styles.draw, { color: colors.plumBrand }]}>{draw}</Text>
        <Pressable
          style={[styles.button, { backgroundColor: colors.plumBrand }]}
          onPress={onPress}
          accessibilityRole="button"
        >
          <Text style={[styles.buttonLabel, { color: colors.paperWhite, fontSize: 13 }]}>
            {goClaim ? 'Claim Your Free Upgrade' : 'Share My Referral Link'}
          </Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={styles.tabCard}>
      <LinearGradient
        colors={[colors.plumDeep, colors.plumBrand]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={StyleSheet.absoluteFill}
      />
      <View style={styles.headRow}>
        <TagPill label="Refer & Win" tone="GOLD" />
        <Text style={[styles.count, { color: colors.paperWhite }]}>
          {done} / {GOAL} Referred
        </Text>
      </View>
      <Text style={styles.tabTitle}>{headline}</Text>
      <Text style={styles.tabBody}>{body}</Text>
      <Text style={[styles.draw, { color: colors.paperWhite }]}>{draw}</Text>
      <Pressable
        style={[styles.button, styles.tabButton]}
        onPress={onPress}
        accessibilityRole="button"
      >
        <MaterialIcons
          name={goClaim ? 'workspace-premium' : 'share'}
          size={16}
          color={colors.plumDeep}
        />
        <Text style={[styles.buttonLabel, { color: colors.plumDeep }]}>
          {goClaim ? 'Claim Your Free Upgrade' : 'Share on WhatsApp'}
        </Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  // Tab: linearGradient(#3B0E4A → #6B2E8F), RCS 18, padding 16.
  tabCard: { borderRadius: 18, overflow: 'hidden', padding: 16 },
  // Detail: PlumTint, 1dp PlumLine, RCS 16, padding 16.
  detailCard: {
    borderRadius: radii.lg,
    backgroundColor: colors.plumTint,
    borderWidth: 1,
    borderColor: colors.plumLine,
    padding: 16,
  },
  headRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  count: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700' },
  tabTitle: {
    marginTop: 8,
    fontFamily: FONTS.serif,
    fontSize: 18,
    lineHeight: 23,
    fontWeight: '700',
    color: colors.paperWhite,
  },
  tabBody: {
    marginTop: 2,
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    lineHeight: 16,
    color: 'rgba(255,255,255,0.8)',
  },
  detailTitle: {
    marginTop: 6,
    fontFamily: FONTS.serif,
    fontSize: 17,
    lineHeight: 22,
    fontWeight: '700',
    color: colors.plumDeep,
  },
  detailBody: {
    marginTop: 4,
    fontFamily: FONTS.sans,
    fontSize: 12,
    lineHeight: 17,
    color: colors.textSecondary,
  },
  // Not in the design: §8.3's mechanic is draw entries + progress, so both are
  // shown (the progress is the "n / 5 Referred" in the pill row).
  draw: {
    marginTop: 6,
    marginBottom: 12,
    fontFamily: FONTS.sans,
    fontSize: 11.5,
    fontWeight: '700',
  },
  button: {
    height: 44,
    borderRadius: 10,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
  },
  tabButton: { backgroundColor: colors.paperWhite },
  buttonLabel: { fontFamily: FONTS.sans, fontSize: 12.5, fontWeight: '700' },
});
