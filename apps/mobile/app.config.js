/**
 * Dynamic Expo config, layered over app.json.
 *
 * Firebase is switched on ONLY when google-services.json is available. The
 * React Native Firebase config plugins hard-fail the native build without it,
 * so including them unconditionally would break every APK build until the
 * Android app is registered in Firebase.
 *
 * Where the file comes from:
 *   - Local builds: .secrets/google-services.json (gitignored).
 *   - EAS builds:   an EAS "file" environment variable, because .secrets is
 *                   never uploaded. Create it once with:
 *       eas env:create --name GOOGLE_SERVICES_JSON --type file \
 *         --value ../../.secrets/google-services.json --visibility secret
 */

const fs = require('node:fs');
const path = require('node:path');
const { AndroidConfig, withAndroidManifest, withDangerousMod } = require('expo/config-plugins');

const LOCAL_GOOGLE_SERVICES = path.resolve(__dirname, '../../.secrets/google-services.json');

function resolveGoogleServicesFile() {
  if (process.env.GOOGLE_SERVICES_JSON) return process.env.GOOGLE_SERVICES_JSON;
  if (fs.existsSync(LOCAL_GOOGLE_SERVICES)) return LOCAL_GOOGLE_SERVICES;
  return null;
}

/**
 * Google Sign-In needs the project's WEB OAuth client id (client_type 3) to
 * obtain an ID token Firebase will accept. It lives in google-services.json,
 * so it is read from there rather than copied by hand and left to drift.
 */
function readGoogleWebClientId(file) {
  try {
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    for (const client of json.client ?? []) {
      const all = [
        ...(client.oauth_client ?? []),
        ...(client.services?.appinvite_service?.other_platform_oauth_client ?? []),
      ];
      const web = all.find((o) => o.client_type === 3);
      if (web) return web.client_id;
    }
  } catch {
    // Fall through — Google Sign-In will be hidden.
  }
  return null;
}

/**
 * Android release builds refuse unencrypted HTTP. That is correct for anything
 * pointed at a real server (ngrok and production are HTTPS), but a local test
 * build talks to the dev API at http://10.0.2.2:4000.
 *
 * So when — and only when — the API this build is configured for is itself
 * plain HTTP, a network security config allows cleartext to THAT host (plus
 * the emulator/loopback addresses Metro uses) and nowhere else. Every other
 * host stays HTTPS-only, so a stray http:// link in content can't leak data
 * even from a local test build, and a build aimed at an HTTPS server never
 * gets any cleartext at all.
 */
function withLocalApiCleartext(config) {
  const apiUrl = process.env.EXPO_PUBLIC_API_URL ?? '';
  if (!apiUrl.startsWith('http://')) return config;
  let apiHost;
  try {
    apiHost = new URL(apiUrl).hostname;
  } catch {
    return config;
  }
  const hosts = [...new Set([apiHost, '10.0.2.2', 'localhost', '127.0.0.1'])];
  const xml = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<network-security-config>',
    '  <base-config cleartextTrafficPermitted="false" />',
    '  <domain-config cleartextTrafficPermitted="true">',
    ...hosts.map((h) => `    <domain includeSubdomains="false">${h}</domain>`),
    '  </domain-config>',
    '</network-security-config>',
    '',
  ].join('\n');

  config = withDangerousMod(config, [
    'android',
    async (cfg) => {
      const dir = path.join(cfg.modRequest.platformProjectRoot, 'app/src/main/res/xml');
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, 'network_security_config.xml'), xml);
      return cfg;
    },
  ]);
  return withAndroidManifest(config, (cfg) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(cfg.modResults);
    app.$['android:networkSecurityConfig'] = '@xml/network_security_config';
    return cfg;
  });
}

module.exports = ({ config }) => {
  const googleServicesFile = resolveGoogleServicesFile();
  const firebaseEnabled = googleServicesFile !== null;
  const googleWebClientId = firebaseEnabled ? readGoogleWebClientId(googleServicesFile) : null;

  return withLocalApiCleartext({
    ...config,
    android: {
      ...config.android,
      ...(firebaseEnabled ? { googleServicesFile } : {}),
    },
    plugins: [
      ...(config.plugins ?? []),
      ...(firebaseEnabled
        ? [
            '@react-native-firebase/app',
            '@react-native-firebase/auth',
            // Deliberately NOT @react-native-firebase/messaging: it registers
            // its own FCM receiver, Android delivers each push to only one
            // receiver, and while the app was open that one dropped messages
            // instead of showing them. expo-notifications handles push alone.
            '@react-native-google-signin/google-signin',
          ]
        : []),
    ],
    extra: {
      ...config.extra,
      // Read at runtime so the app never calls into an unconfigured Firebase.
      firebaseEnabled,
      googleWebClientId,
    },
  });
};
