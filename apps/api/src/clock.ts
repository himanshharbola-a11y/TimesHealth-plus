/**
 * The server's notion of "now" for time-gated rules (the class join window).
 *
 * Tests pin it so a rule like "a join only counts while a class is open" can
 * be checked at any hour the suite happens to run. Production never pins it.
 */
let pinned: Date | null = null;

export function now(): Date {
  return pinned ? new Date(pinned) : new Date();
}

export function pinClockForTests(at: Date | null): void {
  pinned = at;
}
