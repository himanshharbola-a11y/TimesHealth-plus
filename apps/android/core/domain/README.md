# core/domain — business rules (pure Kotlin)

The TimesHealth+ rules that do not depend on a screen or on Android: IST time, the class join window, run-tracker maths, validators, link safety, labels, money, the Yoga calendar and checkout outcomes. They are ported from the React Native app (`apps/mobile`) and from the Node API (`apps/api`) where the app must agree with the server.

- **Pure and deterministic.** There are no Android imports and no hidden clock. Every time-dependent function takes `nowMs: Long` or `today: LocalDate`, or a `java.time.Clock` (`ServerClock`).
- **No `:core:model` types.** Functions take primitives, `String`, `java.time` types and the small domain types defined here (`GeoFix`, `Anchor`, `BatchPlan`, `DobResult`, `YogaMonth`, …). API enums are passed as their wire strings (`"IMPERIAL"`, `"PAID_NOT_GRANTED"`).
- **One flat package**, `timeshealth.app.core.domain`. Every row below is a top-level function, constant or type.
- **Tests:** `./gradlew :core:domain:test` (JUnit 4 + Truth). `RunMathTest` is a one-to-one port of the 13 tests in `apps/mobile/src/lib/runMath.test.ts`.

## Rule map (for the Swift mirror)

PRD references are the `§` numbers quoted in the TypeScript comments. `docs/04` is `docs/04-security-privacy-rbac.md`.

### Run tracker

| Rule | Kotlin | TS source | PRD |
|---|---|---|---|
| Fixes worse than 25 m are dropped, never averaged in | `ACCURACY_GATE_M` | `apps/mobile/src/lib/runMath.ts` | §8.6 |
| Movement under 4 m is standing-still jitter | `MIN_SEGMENT_M` | runMath.ts | §8.6 |
| Over 12 m/s (~43 km/h), measured between fixes, is a GPS jump | `MAX_SPEED_MPS` | runMath.ts | §8.6 |
| A fix older than start/resume by more than 2 s is a cached replay | `STALE_FIX_GRACE_MS` | runMath.ts | §8.6 |
| Great-circle distance (R = 6,371 km) | `haversineM(a: Coordinates, b: Coordinates)` | runMath.ts `haversineM` | §8.6 |
| What a batch of fixes does to a run: drops, jitter, re-anchor after start/resume, jump candidates confirmed by the next fix | `planBatch(anchor, points, reanchorAfterIn, candidateIn): BatchPlan` | runMath.ts `planBatch` | §8.6 |
| A GPS fix / the last stored point | `GeoFix` (TS `GeoPoint`), `Anchor`, `GeoFix.toAnchor()` | `packages/types` `GeoPoint`, runMath.ts `Anchor` | §8.6 |
| Run ids are RFC 4122 v4 (the server rejects anything else) | `uuidV4()`, `uuidV4(random: Random)`, `UUID_RE` | runMath.ts `uuidV4`, `UUID_RE` | §8.6 |
| km/mile display, pace per display unit | `unitsFor(imperial): DistanceUnits` (`short`, `long`, `dist(km)`, `pace(secPerKm)`), `KM_PER_MILE` | `apps/mobile/src/components/RunTrackerPanel.tsx` `unitsFor` | §8.6 |
| Pace `5'58" /km`, total rounded first (no `13'60"`) | `formatPace(secPerKm)` | `apps/mobile/src/lib/time.ts` `formatPace` | §8.6 |
| Elapsed `MM:SS` / `H:MM:SS` | `formatDuration(seconds)` | time.ts `formatDuration` | §8.6 |

### Time, IST and the class join window

| Rule | Kotlin | TS source | PRD |
|---|---|---|---|
| Countdowns run on the server's clock: skew is measured once per response | `ServerClock(deviceClock)`: `syncServerTime(iso \| ms)`, `nowMs()`, `now()`, `skewMs` | time.ts `syncServerTime`, `serverNow` | §6.1 |
| Whole seconds left, rounded half-up, never negative | `secondsUntil(targetMs, nowMs)` | time.ts `useCountdown` | §6.1 |
| "Starts in 1h 5m" / "Starts in 4 min" / "Starts in 42s" / "Starting now" | `formatCountdown(seconds)` | time.ts `formatCountdown` | §6.1 |
| API ISO instants (unparseable → null) | `parseIsoInstant(iso)` | `Date.parse` / `new Date(iso)` | — |
| "Thu, 15 Oct, 6:00 am" | `formatSessionDate(instant, zone = IST)` | time.ts `formatSessionDate` | — (design) |
| "6:00 am" | `formatTimeOfDay(instant, zone = IST)` | time.ts `formatTimeOfDay` | — (design) |
| "Today · 7:30 pm" / "Tomorrow · …" / "Wed · …", by calendar day on the server clock | `formatDayAndTime(instant, nowMs, zone = IST)` | time.ts `formatDayAndTime` | — (design) |
| All scheduling is IST, a fixed +05:30 offset | `IST` | `apps/api/src/time.ts` `IST_OFFSET_MINUTES` | §7.1 |
| The IST calendar day of an instant | `istDate(instant): LocalDate` | (helper) | §7.1 |
| IST midnight as an instant (Attendance.date) | `istDateOnly(instant)` | api time.ts `istDateOnly` | §7.1 |
| UTC midnight of the IST day (DATE columns; avoids "a day early") | `istCalendarDate(instant)` | api time.ts `istCalendarDate` | §7.1 |
| "YYYY-MM-DD" in IST (the ledger key) | `istDateString(instant)` | api time.ts `istDateString` | §7.1 |
| First instant of the IST month | `istMonthStart(instant)` | api time.ts `istMonthStart` | §7.1 |
| "HH:mm" IST batch time → instant on an IST day (+ day offset) | `batchInstant(time, dayOffset, from): Instant?` | api time.ts `batchInstant` | §7.1 |
| Minutes past midnight; sort batches by the clock, never by period | `minutesOfDay(time): Int?`, `byClockTime` | api time.ts `minutesOfDay`, `byClockTime` | §7.1 |
| "06:30" → "6:30 AM" | `formatBatchTime(time)` | api time.ts `formatBatchTime` | §7.1 |
| IST hour; whole IST days between instants | `istHour(instant)`, `istDaysUntil(to, from)` | api time.ts `istHour`, `istDaysUntil` | §7.1 |
| "Good morning · Thursday" (IST; 12:00 and 17:00 switch) | `greetingFor(instant)` | api time.ts `greetingFor` | — (design) |
| "27–28 Oct · 10 AM to 6 PM" from the stored instants | `formatIstWindow(start, end)` | api time.ts `formatIstWindow` | — (race pages) |
| Class join window: `start − 60 min ≤ now < start + 60 min` | `isJoinOpen(startMs, nowMs)`, `isJoinOpen(startsAtIso, nowMs)`, `inJoinWindow(start, now)` | `apps/mobile/src/lib/joinClass.ts` `isJoinOpen` = api time.ts `inJoinWindow` | §7.1, §2 |
| Wait room opens 60 min early; a batch runs 60 min | `WAIT_ROOM_MINUTES`, `YOGA_BATCH_DURATION_MINUTES`, `WAIT_ROOM_MS`, `CLASS_MS` | api time.ts, joinClass.ts | §2 |
| Class is live: `start ≤ now < start + 60 min` | `isLiveNow(startMs, nowMs)`, `isLiveNow(start, now)` | api time.ts `isLiveNow` | §2 |

### Identity and profile

| Rule | Kotlin | TS source | PRD |
|---|---|---|---|
| App phone field → `+91XXXXXXXXXX` (Indian mobiles, 6–9 prefix) or null | `toIndianE164(input)` | `apps/mobile/src/lib/firebaseAuth.ts` `toIndianE164` (re-exported by `identity.ts`) | §5 |
| Server phone normalisation (also non-Indian `+` international) | `normalizePhone(raw)` | `apps/api/src/identity/normalize.ts` `normalizePhone` | §5 |
| Server email normalisation (trim, lowercase) | `normalizeEmail(raw)` | normalize.ts `normalizeEmail` | §5 |
| Prefill the 10-digit field from a stored number | `localMobileDigits(phone)` | `apps/mobile/app/onboarding.tsx` `localNumber`, ProfileDrawer | §5, §10 |
| Email accepted by the server (zod `.email()`) | `isValidEmail(email)`, `EMAIL_RE`, `EMAIL_MAX_LENGTH` | onboarding.tsx `EMAIL_RE`, api `routes/session.ts` | §5 |
| Profile drawer's looser email shape check | `isValidProfileEmail(email)`, `PROFILE_EMAIL_RE` | `apps/mobile/src/components/ProfileDrawer.tsx` | §10 |
| DOB: DD/MM/YYYY, a real date, not in the future, age 13–100 | `parseDob(dd, mm, yyyy, today): DobResult`, `DobError` (with copy), `DOB_MIN_AGE`, `DOB_MAX_AGE` | ProfileDrawer.tsx `parseDob` | §10 |
| Stored DOB (UTC midnight) → fields / "1 Feb 1990" | `splitDob(iso): DobParts`, `formatDob(iso)` | ProfileDrawer.tsx `splitDob`, `formatDob` | §10 |
| Goal / concern / gender labels, as worded in onboarding | `GOALS`, `CONCERNS`, `GENDERS`, `goalLabel`, `concernLabel`, `genderLabel`, `LabelOption`, `NO_VALUE` | `apps/mobile/src/lib/profileLabels.ts`; `GENDERS` from ProfileDrawer.tsx | §5, §10 |
| "+919000000004" → "+91 90000 00004", else as stored | `formatPhone(phone)` | profileLabels.ts `formatPhone` | §10 |

### Links, money, Yoga, checkout

| Rule | Kotlin | TS source | PRD |
|---|---|---|---|
| External URLs: https, whatsapp, market, tel and mailto only, plus http in debug | `isSafeExternalUrl(url, debug)`, `allowedSchemes(debug)`, `schemeOf(url)` | `apps/mobile/src/lib/links.ts` | — (docs/04 hardening) |
| Push/inbox routes must be a screen that exists | `isAppRoute(route)`, `APP_ROUTES` | links.ts `isAppRoute` | — (docs/04 hardening) |
| Integer paise → "₹1,00,000" (lakh grouping, half-up) | `formatPaise(paise)` | time.ts `formatPaise` | docs/04 T8 (client never sends a price) |
| Yoga month grid in IST from the server clock | `yogaMonth(nowMs, attendedDates, trackingSince): YogaMonth`, `WEEKDAY_INITIALS` | `apps/mobile/app/(tabs)/yoga.tsx` `MonthCalendar` | §7.1 |
| Day state: today > future > attended > missed (from `trackingSince` until yesterday) > neutral | `dayState(day, today, isoDate, attended, trackingSince)`, `DayState` | yoga.tsx `stateOf` | §7.1 |
| Calendar cell accessibility label | `calendarDayLabel(month, day, state)` | yoga.tsx | §7.1 |
| Order status → IDLE / GRANTED / FAILED / REFUND / SETTLING | `outcomeOf(status, entitlementGranted)`, `CheckoutOutcome` | `apps/mobile/src/components/CheckoutSheet.tsx` `outcomeOf` | docs/04 T8 |
| Stop polling once settled | `isOrderSettled(status, entitlementGranted)` | `apps/mobile/src/api/hooks.ts` `isSettled` | docs/04 T8 |
| One pending order per purchase (no double charge) | `purchaseKey(productType, productId, eventId, category, tier)` | `apps/mobile/src/lib/pendingOrders.ts` `purchaseKey` | docs/04 T8 |
| Trim like JavaScript | `String.trimJs()` | `String.prototype.trim` | — |

## Dialect notes (read before mirroring in Swift)

The rules were written for JavaScript, and the server still runs them. Where JavaScript and the JVM disagree on a primitive, this module uses the JavaScript meaning. Swift needs to do the same.

1. **Whole-string regex matches.** In Java and ICU (`NSRegularExpression`), `$` also matches before a final `\n`, so `find` would accept `"/paywall\n"`. Kotlin uses `Regex.matches`/`matchEntire`. In Swift, use `wholeMatch(of:)` or anchor with `\z`.
2. **`\s` and trimming mean JavaScript whitespace.** This is `JS_WHITESPACE_CLASS` in `JsCompat.kt`: ASCII whitespace plus U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000 and U+FEFF. Java's `\s` is ASCII-only. Kotlin's `trim()` keeps U+FEFF and strips U+001C–U+001F.
3. **Case-insensitive means ASCII only** (`EMAIL_RE`, `SCHEME_RE`, `UUID_RE`). `\d` and `\w` are ASCII.
4. **Rounding is `Math.round`: half toward +∞** (`-1.5 → -1`, `2.5 → 3`). Use Java `Math.round`. Do not use Kotlin `round()` (half-even) or Swift `.rounded()` (half away from zero).
5. **IST is a fixed +05:30 offset** (`TimeZone(secondsFromGMT: 19800)`), not `Asia/Kolkata`.
6. **Names are hard-coded, not taken from locale data.** Session and IST-window dates use ICU en-IN abbreviations, so September is **"Sept"** and the day period is lowercase **"am/pm"**. The DOB row uses its own list, so September is "Sep" there. Batch times use uppercase "AM/PM". Weekday indexes are Sunday-first.
7. **"HH:mm" parts are read like `Number()`.** `" 5"` is 5, `""` is 0, and `"7"` with no minutes is 07:00.

## Behaviour that differs from the TypeScript, on purpose

- **`formatSessionDate`, `formatTimeOfDay` and `formatDayAndTime` default to IST.** The RN app used the phone's time zone, so for a phone set to IST the output is identical. Anyone else would have seen class times that disagree with the batch's "6:00 AM" label. Pass `zone` to override.
- **Some inputs that made NaN in the TS now return null.** `ServerClock.syncServerTime` ignores an unparseable time; the TS set the skew to NaN and every countdown broke until the next sync. A malformed "HH:mm" gives `null` from `batchInstant`/`minutesOfDay`, sorts last in `byClockTime` and is echoed unchanged by `formatBatchTime`. The TS gave NaN, an Invalid Date or "NaN:NaN AM". A fractional part ("5.5:00") is rejected; it was not truncated.
- **`parseIsoInstant` is strict.** It rejects impossible dates such as `2026-02-30T…`, which V8 rolls over to 2 March, and V8's non-ISO formats. The API only sends `toISOString()`, so this has no effect in practice.
- **`formatPaise(-1 … -50)` is "₹0".** JavaScript printed "₹-0". Negative prices don't occur.
- **API shapes.** `isAppRoute` takes `String?` (the TS took `unknown`). `outcomeOf` takes `(status: String?, entitlementGranted)`, where a null status means no response yet. `parseDob` takes `today` explicitly; the TS read the phone's date. `uuidV4()` uses `UUID.randomUUID()`, the equivalent of the TS native `crypto.randomUUID` path; the Math.random fallback is `uuidV4(random)`. `GeoPoint` is renamed `GeoFix`.

## Known disagreements in the source, ported as-is

- **Phone (fixed 2026-10-06 in all three codebases).** The app used to refuse `09876543210`, which the server accepted. The server used to keep `"+91 58765 43210"` as an "international" number. Now both accept every common form of an Indian mobile, and both refuse a `+91` number that isn't one. Only the server accepts other countries' `+` numbers, for contact fields. `PhoneTest` checks the agreement on a table.
- **Email: two patterns.** Onboarding uses the server's zod pattern. The profile drawer uses a looser one, and the server then refuses with the same message. Both are ported. Use `isValidEmail` on every screen.
