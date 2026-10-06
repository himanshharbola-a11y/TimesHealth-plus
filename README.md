# TimesHealth+

Yoga, Marathon and Diet in one Android/iOS app. React Native (Expo) client,
Fastify + Postgres API, Firebase for sign-in and push.

- **What it is and why** → [docs/00-START-HERE.md](docs/00-START-HERE.md)
- **Moving to company accounts** → [docs/06-migration.md](docs/06-migration.md)

---

## Everyday commands

Run from the repo root.

| Command | What it does |
|---|---|
| `npm run dev` | Starts Docker + Postgres if needed, then the API on `localhost:4000` with auto-reload. **Start here.** |
| `npm run serve` | Hosts the API publicly from this PC through your ngrok domain. Keep the window open. Add `-- --qa` to allow test-persona sign-in. |
| `npm run apk` | Builds a signed, installable APK into `dist/`. See [Building an APK](#building-an-apk). |
| `npm run mobile` | Starts the JS dev server for a development build on the emulator. |
| `npm run personas` | (Re)creates the six QA test accounts, clearing any test purchases, runs and seats on them. |
| `npm run seed` | Resets content — sessions, races, workshops, articles. **Wipes registrations.** |
| `npm run db:studio` | Opens a browser UI to view and edit the database. |
| `npm test` | Runs the 39 API rule tests (hero priority, money rules, attendance, auth…) and the 13 run-tracker distance tests, in ~10s. **Run before every change you ship.** |
| `npm run typecheck` | Type-checks the API, app and shared types. |

## First-time setup on a new machine

1. **Install:** Node 20+, Docker Desktop, and (for building APKs) JDK 17 and the
   Android SDK with NDK `27.1.12297006`.
2. `npm install`
3. Copy `.env.example` to `apps/api/.env`.
4. Put credentials in `.secrets/` — see [.secrets/README.md](.secrets/README.md).
5. `npm run dev`, then in another terminal `npm run seed` and `npm run personas`.

## How the pieces fit

```
apps/mobile     React Native (Expo SDK 57) — screens in app/, logic in src/
apps/api        Fastify API — routes in src/routes, rules in src/services
packages/types  Shared TypeScript contract between app and API
packages/config Design tokens extracted from the original design APK
assets/design-reference   Decompiled design prototype — the visual spec
docs/           Planning: dependencies, gaps, architecture, security, launch
scripts/        dev, serve and apk helpers
.secrets/       Credentials — gitignored, never commit
```

**Home is server-driven.** The API decides what appears on Home and in what
order (`apps/api/src/services/feed.ts`); the app just renders the list. To
reorder rails, change the promo strip or hide a section, change the server —
no app release needed.

**Entitlement is decided only by the server** (`services/entitlements.ts`).
The app never decides who has access to what.

## Changing the app without a release

Much of what users see is decided by the server, so you can change it live —
no new APK, no store review. Until there is a proper admin panel, edit these
through `npm run db:studio` (a database UI in your browser):

| To… | Edit table | How |
|---|---|---|
| Change, schedule or switch off the Home promo strip (§6.2) | `PromoCampaign` | Toggle `active`; set `startsAt`/`endsAt`; highest `priority` wins. With none active, the strip simply disappears. |
| Add or change a live workshop | `LiveWorkshop` | Edit price (in **paise** — ₹499 = `49900`), capacity, date, join link. |
| Mark a race result as published (§8.4) | `RaceResult` | Set `published = true`. The user's box flips to "Check Result" and a push goes out within a minute. |
| Close registrations or mark Premium sold out (§8.3) | `MarathonEvent` / `RaceDistanceOption` | `registrationOpen = false`; `premiumSoldOut = true` hides the upgrade banner. |
| Force everyone to update the app | `AppConfig` | Raise `minSupportedAppVersion`. |
| Take the app offline for maintenance | `AppConfig` | `maintenanceActive = true` plus a `maintenanceMessage`. |

Changes show up on the next refresh of the affected screen.

## Testing as different users

In test builds the login screen offers **test personas** — Free, Yoga
subscriber, Marathon registrant, Both, Expired, and Race finisher — so every
state in the PRD can be checked in seconds. They only work while the API runs
with `ALLOW_DEV_TOKENS=true` (or `npm run serve -- --qa`), which the API refuses
in production. Persona sign-in only ever reaches the QA accounts, never a real
user's account.

Real sign-in also works: email/password, Google (once the signing key's
fingerprints are registered in Firebase), and phone OTP with the Firebase test
number `+91 9999900001` / code `123456` until the project is on the Blaze plan.

## Building an APK

```
npm run apk                     # points at your ngrok domain
npm run apk -- --emulator       # points at localhost, for the Android emulator
npm run apk -- --api=https://…  # any other server
npm run apk -- --fast           # quicker rebuild, keeps the previous native build
npm run apk -- --release        # hides the test-persona sign-in
npm run apk -- --all-abis       # universal APK for every CPU type (~2x build time)
```

By default a phone build compiles for the two ARM CPU types (every Android
phone in practice, including older 32-bit and Android Go devices), and an
emulator build for x86_64 only — native C++ is compiled once per CPU type, so
this is the biggest build-time lever.

The first build downloads Gradle and takes 15–25 minutes; later ones are much
faster. APKs are signed with `.secrets/timeshealth-upload.jks` — **back up
`.secrets/`**. If that key is lost, new builds have a different fingerprint and
Google sign-in stops working until the new one is registered in Firebase.

Install on a phone: copy the APK across and open it (allow "install unknown
apps"), or with the phone on USB debugging: `adb install -r dist/<file>.apk`.

## Hosting from this PC

`npm run serve` makes the API reachable from any phone, anywhere, through your
fixed ngrok address. The app works only while that window is open and the PC is
awake.

Test-persona sign-in is **off** on the tunnel unless you start it with
`npm run serve -- --qa`, whatever `apps/api/.env` says. With `--qa`, anyone who
has the address can sign in as a test persona and make test-mode purchases.
That is fine for team testing; don't share the address beyond that.

## Changing the database schema

1. Edit `apps/api/prisma/schema.prisma`.
2. **Stop the API first** (Ctrl+C on `npm run dev`). On Windows the running API
   locks Prisma's engine file and the next step fails with `EPERM`.
3. `npm run db:push`, then `npm run dev` again.

`db:push` is right for a test database. Before real user data, switch to
migrations — see [docs/06-migration.md](docs/06-migration.md#5-backend-on-company-infrastructure).

## Known gaps — not built yet

| Gap | Waiting on |
|---|---|
| Real payments (test-mode gateway today) | IAP-vs-web decision, merchant account — [docs/04 §5](docs/04-security-privacy-rbac.md#app-store-billing-rules) |
| Auto-renewing yoga subscriptions — today a purchase buys 1 or 12 months, and the paywall and profile say so ("Valid until", not "Renews") | The same payment-rail decision: renewals need IAP or a UPI Autopay / e-mandate |
| WhatsApp-attributed attendance | Per-user class link from the yoga team — [docs/01 §B4](docs/01-api-dependencies.md) |
| Diet leads reaching the existing pipeline | `DIET_LEAD_WEBHOOK_URL` (leads are stored safely meanwhile) |
| Real class, race and diet content | Content team; imagery is placeholder |
| Refer & Win attribution | Install-time referral capture |
| iOS build | Apple Developer account |
