# TimesHealth+ API — Spring Boot + Kotlin

The Kotlin replacement for the Node/Fastify API in `apps/api`. It is a **drop-in replacement**: the same HTTP contract and the same Postgres database and schema. The app doesn't change when the server switches.

**How the switch-over works**
- The Node API stays the reference, and keeps running staging, until every route is ported.
- Each ported route must give the same answers as the Node one (see *Parity* below).
- Then the app's server URL switches over.

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
./gradlew bootRun
```

**Demo data:** until `seed.ts` and `personas.ts` are ported to Kotlin, use the Node scripts. They write the same tables: `npm run seed && npm run personas` from the repo root.

## Test

```bash
./gradlew build      # compile + tests (Docker must be running for Testcontainers)
```

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
| `routes/yoga.ts` + `services/attendance.ts` | — | To do |
| `routes/marathon.ts` + `services/media.ts` (bib/playback signing) | — | To do |
| `routes/orders.ts` (pricing, settlement, referrals) | — | To do |
| `routes/runs.ts`, `routes/diet.ts` + `services/dietLeads.ts` | — | To do |
| `routes/content.ts` + `services/workshops.ts`, `contentCache.ts` | — | To do |
| `services/feed.ts` (Home) | — | To do |
| `routes/devices.ts`, `services/push.ts`, `services/scheduler.ts` | — | To do |
| `prisma/seed.ts`, `personas.ts` → Kotlin demo-data loader | — | To do |

All JPA entities for every table already exist (`db/entity/`), so the remaining ports are service and controller logic.
