#!/usr/bin/env node
/**
 * Builds a signed, installable Android APK on this machine.
 *
 *   npm run apk                 → API = your ngrok domain (from .secrets/NGROK_DOMAIN)
 *   npm run apk -- --emulator   → API = the emulator's view of localhost
 *   npm run apk -- --api=https://api.example.com/v1
 *
 * Output: dist/TimesHealth-<version>-<api>.apk
 *
 * Needs JDK 17 and the Android SDK (both installed under %LOCALAPPDATA%).
 * Signs with .secrets/timeshealth-upload.jks — the key whose fingerprints are
 * registered in Firebase. Lose that file and Google sign-in breaks for new
 * builds, so keep a backup of .secrets/ somewhere safe.
 */

import { execSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MOBILE = path.join(ROOT, 'apps', 'mobile');
const SECRETS = path.join(ROOT, '.secrets');
const LOCALAPPDATA = process.env.LOCALAPPDATA ?? path.join(homedir(), 'AppData', 'Local');

function fail(msg) {
  console.error(`\n✖ ${msg}\n`);
  process.exit(1);
}

function readSecret(name) {
  const p = path.join(SECRETS, name);
  return existsSync(p) ? readFileSync(p, 'utf8').trim() : null;
}

function findJdk17() {
  if (process.env.JAVA_HOME?.includes('17')) return process.env.JAVA_HOME;
  const base = path.join(LOCALAPPDATA, 'Programs');
  const dir = existsSync(base) ? readdirSync(base).find((d) => d.startsWith('jdk-17')) : null;
  return dir ? path.join(base, dir) : null;
}

// ── Resolve inputs ───────────────────────────────────────────────────────────

const args = process.argv.slice(2);
const apiArg = args.find((a) => a.startsWith('--api='))?.slice(6);
const ngrokDomain = readSecret('NGROK_DOMAIN');

let apiUrl;
let apiLabel;
if (apiArg) {
  apiUrl = apiArg;
  apiLabel = 'custom';
} else if (args.includes('--emulator')) {
  apiUrl = 'http://10.0.2.2:4000/v1';
  apiLabel = 'emulator';
} else if (ngrokDomain) {
  apiUrl = `https://${ngrokDomain.replace(/^https?:\/\//, '').replace(/\/+$/, '')}/v1`;
  apiLabel = 'ngrok';
} else {
  fail('No API address. Add .secrets/NGROK_DOMAIN, or pass --emulator or --api=<url>.');
}

const props = readSecret('keystore.properties');
if (!props) fail('Missing .secrets/keystore.properties (the signing key settings).');
const kv = Object.fromEntries(
  props.split(/\r?\n/).filter(Boolean).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]),
);
const keystore = path.join(SECRETS, kv.storeFile);
if (!existsSync(keystore)) fail(`Signing key not found: ${keystore}`);

const javaHome = findJdk17();
if (!javaHome) fail('JDK 17 not found. Android builds do not support newer JDKs yet.');
const androidHome = process.env.ANDROID_HOME ?? path.join(LOCALAPPDATA, 'Android', 'Sdk');
if (!existsSync(androidHome)) fail(`Android SDK not found at ${androidHome}`);

const version = JSON.parse(readFileSync(path.join(MOBILE, 'app.json'), 'utf8')).expo.version;

// CPU types to compile native code for. Each one repeats the whole C++ build,
// so building only what the target needs is the biggest single time saving:
//   emulator → x86_64 only
//   phones   → arm64-v8a (all modern phones) + armeabi-v7a (older and
//              Android Go devices, still common in India)
// --all-abis builds a universal APK that runs everywhere, at ~2x the time.
const abis = args.includes('--all-abis')
  ? 'armeabi-v7a,arm64-v8a,x86,x86_64'
  : apiLabel === 'emulator'
    ? 'x86_64'
    : 'armeabi-v7a,arm64-v8a';

console.log(`Building TimesHealth+ ${version}`);
console.log(`  API:      ${apiUrl}`);
console.log(`  CPUs:     ${abis}`);
console.log(`  Firebase: ${existsSync(path.join(SECRETS, 'google-services.json')) ? 'on' : 'OFF (no google-services.json)'}`);

const env = {
  ...process.env,
  ORG_GRADLE_PROJECT_reactNativeArchitectures: abis,
  JAVA_HOME: javaHome,
  ANDROID_HOME: androidHome,
  ANDROID_SDK_ROOT: androidHome,
  EXPO_PUBLIC_API_URL: apiUrl,
  // Test APKs show the QA persona sign-in. A server must ALSO opt in with
  // ALLOW_DEV_TOKENS=true to accept them. Drop this for a real release.
  EXPO_PUBLIC_DEV_SIGNIN: args.includes('--release') ? '0' : '1',
  NODE_ENV: 'production',
  CI: '1',
};

// ── Build ────────────────────────────────────────────────────────────────────

// --fast reuses the existing native project as-is and goes straight to Gradle,
// keeping its incremental state: a rebuild after JS-only changes takes minutes.
// (Expo's prebuild regenerates android/ from scratch whenever the folder is
// gitignored, as ours is, so "prebuild without --clean" is never incremental.)
//
// Use --fast ONLY when app.json / app.config.js / native plugins are unchanged.
// It falls back to a full regeneration when the native project is incomplete,
// or when the existing project's cleartext setting doesn't match this build:
// an HTTPS build must never ship with cleartext switched on, and a local HTTP
// build without it can't reach its own API. (Cleartext comes from the network
// security config app.config.js writes only for a plain-HTTP API.)
const ANDROID = path.join(MOBILE, 'android');
const nativeIntact = ['gradlew', 'settings.gradle', path.join('app', 'build.gradle')].every((f) =>
  existsSync(path.join(ANDROID, f)),
);
const manifestPath = path.join(ANDROID, 'app', 'src', 'main', 'AndroidManifest.xml');
const manifestAllowsCleartext =
  existsSync(manifestPath) &&
  /usesCleartextTraffic="true"|networkSecurityConfig=/.test(readFileSync(manifestPath, 'utf8'));
const cleartextMismatch = apiUrl.startsWith('https://') ? manifestAllowsCleartext : !manifestAllowsCleartext;

const fast = args.includes('--fast') && nativeIntact && !cleartextMismatch;
if (args.includes('--fast') && !fast) {
  console.log(
    `\n  --fast ignored: ${!nativeIntact ? 'native project is incomplete' : 'existing project’s plain-HTTP setting doesn’t match this build’s API URL'}.`,
  );
}

if (fast) {
  console.log('\n[1/3] Reusing existing native project (--fast)…');
} else {
  console.log('\n[1/3] Generating native project…');
  try {
    execSync('npx expo prebuild --platform android --clean --no-install', {
      cwd: MOBILE,
      env,
      stdio: 'inherit',
    });
  } catch {
    fail('Native project generation failed — see the output above. (On Windows, make sure no terminal or editor is open inside apps/mobile/android.)');
  }
}

console.log('\n[2/3] Compiling and signing (first run downloads Gradle — 15-25 min)…');
const androidDir = path.join(MOBILE, 'android');
// Full, quoted path: cmd.exe is unreliable about finding scripts in the
// working directory, and the repo path contains a space ("Health App").
const gradle = `"${path.join(androidDir, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')}"`;

// Signing settings go in through ORG_GRADLE_PROJECT_* environment variables,
// which Gradle reads as project properties — never on the command line. A
// command line is visible to every process on the machine and is echoed in
// full by Node when a build fails, which would print the key's password.
const signingEnv = {
  'ORG_GRADLE_PROJECT_android.injected.signing.store.file': keystore,
  'ORG_GRADLE_PROJECT_android.injected.signing.store.password': kv.storePassword,
  'ORG_GRADLE_PROJECT_android.injected.signing.key.alias': kv.keyAlias,
  'ORG_GRADLE_PROJECT_android.injected.signing.key.password': kv.keyPassword,
};

try {
  execSync(`${gradle} assembleRelease --console=plain`, {
    cwd: androidDir,
    env: { ...env, ...signingEnv },
    stdio: 'inherit',
  });
} catch {
  // Deliberately not printing the error object: it carries the command and
  // environment. Gradle's own output above already says what went wrong.
  fail('Gradle build failed — see "What went wrong" in the output above.');
}

console.log('\n[3/3] Collecting APK…');
const built = path.join(MOBILE, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
if (!existsSync(built)) fail('Gradle finished but no APK was produced.');
mkdirSync(path.join(ROOT, 'dist'), { recursive: true });
const out = path.join(ROOT, 'dist', `TimesHealth-${version}-${apiLabel}.apk`);
copyFileSync(built, out);
console.log(`\n✔ ${out}\n`);
