# Integration handover: what the tech team plugs in

The app runs end to end today on stand-ins: a mock payment rail, test sign-in personas,
our own Kotlin server and demo data. Every outside dependency sits behind an interface, so
replacing a stand-in means adding a class and changing one binding line. No screen,
ViewModel or repository should need to change.

## 1. Inside the app: outside SDKs

All of these are chosen in one file, `app/src/main/kotlin/timeshealth/app/wiring/IntegrationsModule.kt`.
The interfaces are in `core/integrations`, and each interface's KDoc has the step-by-step "HOW TO PLUG IN".

| Dependency | Interface | Stand-in today | Real system | Change |
|---|---|---|---|---|
| Subscriptions and payments | `SubscriptionProvider` | `ServerCheckoutSubscriptionProvider`: server checkout with a simulated payment | TIL Subscription SDK | New class + 1 `@Binds` line |
| Sign-in (SSO) | `IdentityGateway` (`core/data`), `InteractiveSignIn` (`ui/login`) | Firebase, or QA personas when Firebase isn't configured | TIL SSO SDK | Bind in `AppModule.identity()` and `UiWiring.interactiveSignIn` |
| Analytics | `AnalyticsTracker` (a set) | Logcat, debug builds only | GrowthRx, Google Analytics | Add 1 `@IntoSet` line per tracker |
| Push | `PushHandler` (a set) + Firebase Messaging | Our own notifications | GrowthRx push campaigns | Add 1 `@IntoSet` line |
| In-app campaigns | `InAppCampaigns` | No-op | GrowthRx in-app | 1 `@Binds` line |
| Video (live and recorded) | `VideoSourceResolver` (a set) | Direct HLS/MP4 URLs (`"url"`) | Slike (`"slike"`) | Add 1 `@IntoSet` line; PMs then enter Slike ids in the dashboard |
| Run map | `RouteMapRenderer` | MapLibre + OpenFreeMap (no key needed) | Google Maps SDK + key | 1 `@Binds` line |

## 2. Behind the API: backend data

The app talks to one REST contract, `core/network/TimesHealthApi.kt`, whose models are in
`core/model`. The base URL is a build flag: `-PapiBaseUrl=https://<host>/v1`. Anything that
answers that contract can sit behind it.

| Need | Where it's decided today | What the tech team does |
|---|---|---|
| Subscription status (who is a member, until when) | Server: `session/EntitlementService.kt` reads our tables | Read from the TIL subscription system there. The app only sees `/session` → `entitlements` and `persona`. |
| Existing users and their data | Server: our Postgres (`User`, `YogaSubscription`, `MarathonRegistration`, …) | Migrate or sync into these tables, or replace the repositories behind the same endpoints |
| Sync with the website | Server | Point the website and the app at the same backend, or sync both ways at the server; the app needs no change |
| Personalisation (what Home shows, and in what order) | Server: `feed/HomeFeedService.kt`, laid out from the admin CMS sections | Swap in a recommender behind `GET /v1/home`; the app renders whatever components come back |
| Live and recorded video URLs | Server: `media/MediaSigning.kt` + dashboard video fields | Slike signing / DRM tokens there |
| Endpoints not yet ported to Kotlin | Server: `web/NodeProxy.kt` forwards them to the Node API | Port, or point at the real service |
| Dashboard sign-in (RBAC) | Server: `admin/AdminSecurity.kt`, `AdminAuthenticator`; roles OWNER / EDITOR / VIEWER | Add an SSO `AdminAuthenticator` |

## 3. Built here and needs no integration

- Every screen and flow in the app:
  - Home (laid out from the CMS);
  - Yoga: schedule, reminders, tracker, library, player, live classes (premieres);
  - Marathon: races, checkout, digital bib (works offline), results, participant details;
  - GPS run tracker;
  - Diet lead form;
  - profile, inbox, onboarding, paywall.
- Loading, slow-network and failure handling:
  - cached responses are shown first and refreshed quietly;
  - pull to refresh;
  - "check your connection" with a retry button;
  - optional sections drop out on failure rather than blanking the screen;
  - onboarding saves retry in the background;
  - payments that are still confirming can never be charged twice.
- The admin dashboard at `/admin` on the Kotlin server:
  - sections, categories, sessions, live classes, instructors, marathons, passes, results;
  - roles and an audit trail.

## 4. Content and business inputs still placeholders

| Item | Where |
|---|---|
| WhatsApp support number, privacy policy URL | `core/domain/.../Links.kt` (`SUPPORT_WHATSAPP_URL`, `PRIVACY_URL`) |
| Diet member testimonials (the design's sample quotes) | `ui/diet/DietScreen.kt` `RESULTS`: replace with real, consented quotes |
| Yoga plan prices on the paywall (display only; the server prices the order) | `ui/paywall/Paywall.kt` `YogaPlans`; should come from the subscription SDK |
| Session media (seeded URLs point to `.invalid`; debug builds play a labelled sample) | Upload real media, or set Slike ids in the dashboard |
