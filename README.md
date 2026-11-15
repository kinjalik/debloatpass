# DebloatPass

[![build](https://github.com/kinjalik/debloatpass/actions/workflows/build.yml/badge.svg)](https://github.com/kinjalik/debloatpass/actions/workflows/build.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/kinjalik/debloatpass/badge)](https://scorecard.dev/viewer/?uri=github.com/kinjalik/debloatpass)

Android adapter for loyalty programs that wrap a perfectly normal `.pkpass`
into deeplinks for adware wallet viewers:

```
walletunion://import/https://host/card/xxx.pkpass
passeswallet://import/https://host/card/xxx.pkpass
walletpasses://import/https://host/card/xxx.pkpass
walletpasses://import?url=https%3A%2F%2Fhost%2Fxxx.pkpass
```

(The list of schemes is in [`deeplinks.gradle.kts`](deeplinks.gradle.kts).)

DebloatPass registers these schemes, extracts the real URL, downloads the pass,
checks it is actually a pkpass (zip + `pass.json`) and hands it to whatever
handles `application/vnd.apple.pkpass` on your phone (FossWallet, Google Wallet, …).

If a wallet app is installed, it opens automatically. The screen also has buttons to
open, share, or save the file somewhere.

You can also open the app and paste a link, or share a link (deeplink or a direct `.pkpass`
URL) from the browser to DebloatPass.

No AndroidX, no dependencies, only the `INTERNET` permission.

## Install

[**Get it on Obtainium**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/kinjalik/debloatpass)
— or add `https://github.com/kinjalik/debloatpass` in Obtainium manually.

Or download the APK from [Releases](https://github.com/kinjalik/debloatpass/releases).

Release signing certificate (SHA-256):

```
CC:44:0B:B3:1E:69:5A:3A:9C:58:12:0A:90:50:53:C6:20:8B:A6:55:BB:10:32:40:4B:B1:32:B1:71:68:4D:1A
```

Every release APK has a signed build provenance attestation, and `SHA256SUMS` is attached to the
release. To check that an APK was built by this repo's CI from the tagged commit:

```
gh attestation verify debloatpass-v1.0.0.apk -R kinjalik/debloatpass
```

If a viewer app is already installed, Android might send the link to it instead.
Uninstall the viewer, or pick DebloatPass in the "Open with" dialog.

## Adding a deeplink scheme

Add the scheme to [`deeplinks.gradle.kts`](deeplinks.gradle.kts) and rebuild:

```kotlin
extra["deeplinkSchemes"] = listOf(
    "walletunion",
    "passeswallet",
    "walletpasses",
    "newwallet",   // <- newwallet://import/https://...
)
```

The manifest intent-filter and the link parser are both generated from this list. The build
fails if a scheme is invalid or is a reserved one like `https`/`content`.

If a link has no URL inside (e.g. `newwallet://card/<id>`), you also have to teach
`PassLink.extract` how to build the URL.

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

- `app/src/test` — JUnit unit tests. `PassLinkTest` (Robolectric, for `android.net.Uri`) covers
  every deeplink shape; `PassValidationTest` checks zip/`pass.json` validation and file naming;
  `PassDownloaderTest` runs real downloads against a local HTTP server (redirects, errors,
  non-pkpass responses, the 20 MB limit).
- `app/src/androidTest` — instrumented tests: every scheme in `deeplinks.gradle.kts` resolves to
  `ImportActivity`, and a `walletunion://import/http://127.0.0.1:<port>/test.pkpass` deeplink
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
| `emulator.yml` | instrumented tests on API 24 and 34 emulators |
| `security.yml` | [zizmor](https://docs.zizmor.sh) for the workflows and [OpenSSF Scorecard](https://scorecard.dev) (→ code scanning, badge) |
| `release.yml` | release-please and the signed release build (see below) |

All actions are pinned by commit SHA; Dependabot (`.github/dependabot.yml`) bumps them and the
Gradle dependencies in weekly grouped PRs.

## Releasing

Releases are cut by [release-please](https://github.com/googleapis/release-please) from
[Conventional Commits](https://www.conventionalcommits.org): `fix: …` → patch, `feat: …` → minor,
`feat!: …` → major. Other commits (`ci:`, `chore:`, `docs:` …) don't trigger a release.

1. release-please keeps a `chore(main): release x.y.z` PR open with the new
   [`version.txt`](version.txt) and `CHANGELOG.md`.
2. Merge it when you want to release. That creates the `vX.Y.Z` tag and the GitHub Release.
3. `release.yml` then builds the APK from the tag, signs it, and attaches
   `debloatpass-vX.Y.Z.apk`, `SHA256SUMS` and a provenance attestation. Obtainium picks it up.

`versionName` is read from `version.txt`; `versionCode` is derived from it
(`1.2.3` → `10203`), so there is nothing to bump by hand.

Repository settings this needs:

- Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests"
  (for the release PR).
- The signing secrets below, as repository secrets or in the `release` environment
  (Settings → Environments → release; the environment is created on the first release run).

To force a specific version, add a `Release-As: x.y.z` footer to a commit message
(an empty commit is fine): `git commit --allow-empty -m "chore: release 2.0.0" -m "Release-As: 2.0.0"`.

CI needs these secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 debloatpass-release.jks` |
| `SIGNING_STORE_PASSWORD` | from `keystore.properties` |
| `SIGNING_KEY_ALIAS` | `debloatpass` |
| `SIGNING_KEY_PASSWORD` | from `keystore.properties` |

## License

[MIT](LICENSE)
