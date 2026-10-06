/**
 * Identifier normalisation — the PRD §5 one-account rule depends on it.
 *
 * Matching is exact string equality, so "Ravi@Gmail.com" vs "ravi@gmail.com",
 * or "9876543210" vs "+919876543210", would otherwise be two different people
 * and a migrating web subscriber would land in an empty new account. Every
 * email/phone is normalised before it is stored AND before it is matched; the
 * legacy import job must use these same functions.
 */

export function normalizeEmail(raw: string | null | undefined): string | null {
  const v = raw?.trim().toLowerCase();
  return v ? v : null;
}

/**
 * Indian mobiles → E.164 (+91XXXXXXXXXX) from any common way of writing them
 * ("98765 43210", "09876543210", "+91-98765-43210", "919876543210"). Other
 * international numbers keep their +country digits. Anything else is not a
 * phone number we can match on → null.
 */
export function normalizePhone(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  const digits = trimmed.replace(/\D/g, '');
  const indianMobile = (d: string) => /^[6-9]\d{9}$/.test(d);
  if (indianMobile(digits)) return `+91${digits}`;
  if (digits.length === 11 && digits.startsWith('0') && indianMobile(digits.slice(1))) {
    return `+91${digits.slice(1)}`;
  }
  if (digits.length === 12 && digits.startsWith('91') && indianMobile(digits.slice(2))) {
    return `+${digits}`;
  }
  // Other countries keep their +country digits. A "+91…" number that wasn't
  // a valid Indian mobile above is a typo, not an international number.
  if (trimmed.startsWith('+') && !digits.startsWith('91') && digits.length >= 8 && digits.length <= 15) {
    return `+${digits}`;
  }
  return null;
}
