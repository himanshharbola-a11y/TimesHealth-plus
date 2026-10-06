import type { Concern, HealthGoal } from '@th/types';

/**
 * The onboarding wording (design prototype) for goals and concerns, shared so
 * the profile repeats exactly what the user picked — "Sleep & energy", not a
 * title-cased enum.
 */

export const GOALS: { value: HealthGoal; label: string; emoji: string }[] = [
  { value: 'WEIGHT_LOSS', label: 'Lose weight', emoji: '🔥' },
  { value: 'STRENGTH_FLEXIBILITY', label: 'Build strength & flexibility', emoji: '🧘' },
  { value: 'STRESS_ANXIETY', label: 'Reduce stress & anxiety', emoji: '🍃' },
  { value: 'MARATHON_TRAINING', label: 'Train for a marathon race', emoji: '🏃' },
  { value: 'CONSISTENCY', label: 'Just stay consistent daily', emoji: '📅' },
];

export const CONCERNS: { value: Concern; label: string }[] = [
  { value: 'LOWER_BACK', label: 'Lower back' },
  { value: 'KNEES_JOINTS', label: 'Knees & joints' },
  { value: 'NECK_SHOULDERS', label: 'Neck & shoulders' },
  { value: 'HIPS_PELVIS', label: 'Hips & pelvis' },
  { value: 'SLEEP_ENERGY', label: 'Sleep & energy' },
  { value: 'NONE', label: 'Nothing specific' },
];

export const goalLabel = (v: HealthGoal | null | undefined): string =>
  GOALS.find((g) => g.value === v)?.label ?? '—';

export const concernLabel = (v: Concern | null | undefined): string =>
  CONCERNS.find((c) => c.value === v)?.label ?? '—';

/** "+919000000004" → "+91 90000 00004". Anything else is shown as stored. */
export function formatPhone(phone: string | null | undefined): string {
  if (!phone) return '—';
  const m = /^\+91(\d{5})(\d{5})$/.exec(phone);
  return m ? `+91 ${m[1]} ${m[2]}` : phone;
}
