# TimesHealth+ API — Spring Boot + Kotlin

The Kotlin replacement for the Node/Fastify API in `apps/api`. It is a **drop-in replacement**: the same HTTP contract and the same Postgres database and schema. The app doesn't change when the server switches.

**How the switch-over works**
- The Node API stays the reference, and keeps running staging, until every route is ported.
- Each ported route must give the same answers as the Node one (see *Parity* below).
- **The app can point at this server already.** Any `/v1` route not ported yet is forwarded to the
  Node server at `NODE_UPSTREAM_URL` and its answer relayed (`web/NodeProxy.kt`). Each new port
  simply stops reaching Node; when nothing is left, delete the proxy.

New features live only here: the **admin CMS + dashboard**, the Home feed built from it, and
**live classes (premieres)**. See *Admin dashboard* below.

## Stack

| Concern | Choice |
|---|---|
| Framework | Spring Boot 3.5 (Spring MVC), Kotlin 2.1, JDK 17 |
| Persistence | Spring Data JPA (Hibernate 6) for entities; `JdbcClient` for Postgres-specific statements (`FOR UPDATE` locks, atomic claim updates, `ON CONFLICT DO NOTHING`, advisory locks) |
| Schema | Flyway. `V1__baseline.sql` is generated from the Prisma schema. Existing databases are adopted via baseline-on-migrate, so V1 isn't re-run. Table/column names are used exactly as Prisma created them (quoted PascalCase/camelCase). |
| JSON | kotlinx.serialization with the **Android app's own API models**: `apps/android/core/model` and `core/domain` are compiled into this server, so app and server share one contract and can't drift |
| Security | Spring Security, stateless, with a bearer filter ported from `apps/api/src/auth.ts`: Firebase ID tokens via Firebase Admin, QA persona tokens (non-prod only, QA identities only), and the identity resolution rules |
| Rate limiting | Bucket4j, 300/min per hashed bearer token or per IP |
| Errors | `{code, message, fields?}`, the same codes and statuses as the Node server |
| Tests | JUnit 5 + Testcontainers (real Postgres) + MockMvc |

## Run

Configuration uses the **same environment variable names** as the Node server, so `apps/api/.env` works for both.

```bash
cd apps/server
set -a; . ../api/.env; set +a        # same .env as the Node API
export PORT=4100                     # alongside Node on 4000
export NODE_UPSTREAM_URL=http://localhost:4000   # unported routes go to Node
./gradlew bootRun
```

Settings only this server reads:

| Variable | Purpose |
|---|---|
| `NODE_UPSTREAM_URL` | Where unported `/v1` routes are forwarded (empty: they are 404s). If Node sits behind this server, give Node `TRUST_PROXY_HOPS` = its own proxies + 1. |
| `ADMIN_BOOTSTRAP_EMAIL`, `ADMIN_BOOTSTRAP_PASSWORD` | Creates the first dashboard owner when there are no admins yet (password 12+ characters). Remove after first sign-in. |
| `ADMIN_SESSION_SECRET` | Signs dashboard sessions. Required (32+ characters) in production. |

**Building from the command line while VS Code is open:** its Java extension builds this project
in the background. Use a separate output folder and the in-process compiler, or the two builds
corrupt each other: `./gradlew test -PbuildDirName=build-cli -Pkotlin.compiler.execution.strategy=in-process`.

**Demo data:** until `seed.ts` and `personas.ts` are ported to Kotlin, use the Node scripts. They write the same tables: `npm run seed && npm run personas` from the repo root.

## Test

```bash
./gradlew build      # compile + tests (Docker must be running for Testcontainers)
```

## Admin dashboard

Open `http://localhost:4100/admin/` (production: `https://<api-host>/admin/`) and sign in.

What PMs can do without a release (changes reach the app within a minute):

- **Home layout**: add, rename, reorder (drag), hide, schedule (show from/until) and target
  (everyone / members / non-members) the sections of the app's Home. Section types: hero cards,
  recommended-for-you, category rail, free sessions, **hand-picked videos** (pick and order them),
  upcoming live classes, promo strip, run tracker tile, workshops, instructor reels, TOI & ET
  articles, testimonials (`cms/SectionKind.kt`).
- **Yoga**: categories, videos (a Slike media id or a stream URL; free or members-only; order;
  hide), instructors.
- **Live classes**: the daily batch timetable, live classes (premieres: a pre-recorded video
  streamed at a set time, everyone at the same point), **"Schedule a week"** to create a class per
  day for a batch in one go, workshops.
- **Content**: articles, instructor reels, testimonials, promo campaigns.
- **Marathon**: editions, distances and prices (Classic/Premium), FAQs.
- **App switches**: minimum app version (force update), maintenance mode.
- **Team**: admins with roles (Owner / Editor / Viewer) and an activity log of every change.

How it is built (to extend it):

- Every editable table is declared once in `admin/AdminResources.kt` (its fields, types,
  validation, list columns). The API (`admin/AdminCrud.kt`) and the dashboard
  (`resources/static/admin`, plain JS, no build step) are generated from it. **A new table or
  column is one declaration**; no controller, SQL or page code.
- Sign-in is a plug-in point (`AdminAuthenticator`): email + bcrypt password today; company SSO
  replaces it by issuing the same session (see the KDoc). Sessions are signed, HttpOnly,
  SameSite=Strict cookies; writes also need a dashboard header, and `/admin` never answers CORS.
- Schema: `db/migration/V2__admin_cms.sql`, mirrored in `apps/api/prisma/schema.prisma` so the
  Node server's `prisma db push` keeps the tables. V2 seeds the default Home layout (the order
  Home had before it became editable).

## Parity with the Node server

Run both servers against the **same** database (Node on 4000, Kotlin on 4100), then:

```bash
python scripts/parity.py          # read-only: config, every persona's session, error shapes
python scripts/parity_writes.py   # onboarding, profile edits (incl. every refusal), account deletion
```

Each script sends identical requests to both servers and diffs the JSON. Timestamps and generated ids are ignored.

**Current result: 12/12 and 17/17 identical.**

## Port status

| Node route / service | Kotlin | Status |
|---|---|---|
| `app.ts` (security headers, CORS, rate limit, errors, 404, health) | `web/`, `error/`, `health/` | ✅ |
| `auth.ts` + `identity/*` | `security/`, `identity/` | ✅ |
| `routes/session.ts` (config, session, onboarding, profile, account) | `session/` | ✅ parity-verified |
| `services/entitlements.ts` | `session/EntitlementService.kt` | ✅ |
| `services/feed.ts` (Home) | `feed/` (now built from CMS sections) | ✅ same content for all 6 personas |
| — (new) admin CMS + dashboard | `cms/`, `admin/`, `static/admin` | ✅ 18 tests + browser walkthrough |
| — (new) live classes `GET /yoga/live`, `POST /yoga/live/{id}/join` | `yoga/LiveClassService.kt` | ✅ |
| `routes/yoga.ts` + `services/attendance.ts` | `yoga/AttendanceService.kt` (record, best streak); `GET /yoga/sessions/{id}/playback` in `yoga/SessionPlaybackService.kt` (adds the dashboard's video for the app's video plug-in) | Partly |
| `routes/marathon.ts` + `services/media.ts` (bib/playback signing) | — | To do |
| `routes/orders.ts` (pricing, settlement, referrals) | — | To do |
| `routes/runs.ts`, `routes/diet.ts` + `services/dietLeads.ts` | — | To do |
| `routes/content.ts` + `services/workshops.ts`, `contentCache.ts` | `feed/Workshops.kt`, `cms/ContentCache.kt` | Partly (routes to do) |
| `routes/devices.ts`, `services/push.ts`, `services/scheduler.ts` | — | To do |
| `prisma/seed.ts`, `personas.ts` → Kotlin demo-data loader | — | To do |

All JPA entities for every table already exist (`db/entity/`), so the remaining ports are service and controller logic.
