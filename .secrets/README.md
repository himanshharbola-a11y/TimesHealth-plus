# .secrets — local only, never committed

Drop credential files here. This folder is gitignored.

| File | What it is | Where it comes from |
|---|---|---|
| `EXPO_TOKEN` | Expo access token (one line, nothing else) | expo.dev → Account settings → Access tokens |
| `google-services.json` | Firebase config for the Android app | Firebase → Project settings → Your apps → Android |
| `firebase-admin.json` | Firebase service account key — **full admin access, guard it** | Firebase → Project settings → Service accounts |

If any of these leak: revoke the Expo token at expo.dev, and delete the service
account key in Google Cloud → IAM → Service accounts, then generate a new one.
