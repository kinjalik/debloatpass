# Development

How to build, test and check DebloatPass, and what CI runs. Releasing:
[docs/how-to-make-release.md](docs/how-to-make-release.md).

## Build

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Without signing config it is unsigned
(`app-release-unsigned.apk`).

### Signing

Signing values are read from `keystore.properties` in the repo root (gitignored):

```properties
storeFile=../keys/debloatpass-release.jks
storePassword=...
keyAlias=debloatpass
keyPassword=...
```

Or from the environment variables `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.

## Tests

```
./gradlew testDebugUnitTest            # JVM: link parsing, pass validation, downloads
./gradlew connectedDebugAndroidTest    # needs an emulator or device
```

- `app/src/test` — JUnit unit tests. `PassLinkTest` and `DeeplinkTemplateTest` (Robolectric,
  for `android.net.Uri` / `Base64`) cover every template and link shape; `PassValidationTest`
  checks zip/`pass.json` validation (incl. the 1 MB cap) and file naming; `PassDownloaderTest`
  runs real downloads against a local HTTP server (redirects, errors, non-pkpass responses, the
  size limit, the overall deadline, the http→https upgrade).
- `app/src/androidTest` — instrumented tests: a link built from every template in
  `deeplinks.gradle.kts` resolves to `ImportActivity` (and other shapes of the same schemes
  don't), and a `walletunion://import/http://127.0.0.1:<port>/test.pkpass` deeplink
  downloads a fixture pass that `PassProvider` then serves with the right MIME type and size.
  Cleartext HTTP to localhost is allowed only in debug builds
  (`app/src/debug/res/xml/network_security_config.xml`).
  `WalletAppsTest` covers the "is there a wallet app?" screen states with a fake pkpass handler
  in the (test-only) test APK that is switched on and off with `pm enable/disable`: wallet
  installed, Google Wallet's import present but disabled, and no wallet at all.
- `app/src/sharedTest` — the tiny HTTP server and pkpass fixtures used by both.

The test libraries are test-only; the app itself still has no dependencies.

## Code style and CI

```
./gradlew ktlintFormat ktlintCheck   # Kotlin style, configured in .editorconfig
./gradlew lintDebug                  # Android Lint
```

Versions of the Gradle plugins and test libraries live in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

| Workflow | What it does |
|---|---|
| `build.yml` | ktlint, unit tests, Android Lint (→ code scanning), unsigned release build, and a check that two builds from different directories give a byte-identical APK |
| `emulator.yml` | instrumented tests on API 24 (minSdk), 34 and 36 (targetSdk, edge-to-edge) emulators |
| `security.yml` | [zizmor](https://docs.zizmor.sh) for the workflows and [OpenSSF Scorecard](https://scorecard.dev) (→ code scanning, badge) |
| `release.yml` | release-please and the signed release build ([how-to](docs/how-to-make-release.md)) |

CI runs on pushes to `main` and on pull requests. All actions are pinned by commit SHA;
Dependabot (`.github/dependabot.yml`) bumps them and the Gradle dependencies in weekly grouped PRs.

### Dependency verification

Every Gradle artifact (plugins, test libraries, their POMs) is checked against the SHA-256
sums in [`gradle/verification-metadata.xml`](gradle/verification-metadata.xml). Local builds
and PR CI only warn on a mismatch; CI on `main` and the signed release build are strict. After
merging a dependency update, regenerate the file and commit it. Use an **empty** Gradle home:
with a warm cache Gradle skips some metadata files (parent POMs, `.module`) that a fresh CI
runner downloads, and strict verification then fails there.

```
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --dependency-verification=off --write-verification-metadata sha256 ktlintCheck testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest printVersionName
```
