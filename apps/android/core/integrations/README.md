# :core:integrations: plug-in points

Every outside system the app talks to sits behind one interface here. The
app uses the interface and never the vendor SDK, so replacing a mock with
the real SDK means **adding one class and changing one line** in
[`app/.../wiring/IntegrationsModule.kt`](../../app/src/main/kotlin/timeshealth/app/wiring/IntegrationsModule.kt).
No screen, ViewModel or repository changes.

| Plug-in point | Interface | Today | Real one (from the tech team) |
|---|---|---|---|
| Subscriptions & payments | `subscription/SubscriptionProvider` | `ServerCheckoutSubscriptionProvider`: our server's checkout with simulated payment | TIL Subscription SDK |
| Analytics | `analytics/AnalyticsTracker` (any number) | Logcat, debug builds only | GrowthRx, Google Analytics |
| In-app campaigns | `campaigns/InAppCampaigns` | `NoOpInAppCampaigns` | GrowthRx in-app |
| Push messages | `push/PushHandler` (any number) | none: the app's own notification | GrowthRx push |
| Video / live classes | `video/VideoSourceResolver` (one per provider) | `DirectUrlResolver` (`"url"`) | Slike (`"slike"`) |
| Sign-in | `IdentityGateway` (in `:core:data`) | Firebase + QA personas | TIL SSO SDK |

Each interface's KDoc has a numbered **HOW TO PLUG IN** section.

## Rules the adapters must keep

- **Payments:** the phone never decides that a purchase succeeded. Access is
  granted when the server hears from the payment or subscription platform.
  Until then, return `PurchaseOutcome.Confirming` and never offer Pay again.
- **Analytics and campaigns:** never throw into a user flow. `Analytics`
  already isolates trackers; keep SDK calls non-blocking.
- **Push:** return `true` from `handle` only for messages your SDK owns. The
  server's class reminders and race-day messages must still reach the default
  notification.
- **Video:** a resolver returns something playable or throws. Premieres carry
  `premiereStartEpochMs`, so the player can join at "now".

## Tests

`./gradlew :core:integrations:testDebugUnitTest` covers the order-status
mapping (no double charge), analytics fan-out with a failing tracker, video
provider lookup, and push handler priority and fallback.
