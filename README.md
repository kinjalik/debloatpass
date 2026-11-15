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

(The supported link shapes are templates in [`deeplinks.gradle.kts`](deeplinks.gradle.kts).)

DebloatPass registers these links, extracts the real URL, downloads the pass,
checks it is actually a pkpass (zip + `pass.json`) and hands it to whatever
handles `application/vnd.apple.pkpass` on your phone (FossWallet, Google Wallet, …).

If a wallet app is installed, it opens automatically. The screen also has buttons to
open, share, or save the file somewhere.

You can also open the app and paste a link, or share a link (deeplink or a direct `.pkpass`
URL) from the browser to DebloatPass.

The main screen tells you whether any installed app can take a `.pkpass`, and whether Google
Wallet's pkpass import is switched off on your device (Google does that in some regions).

Plain `http://` pass links are fetched over https: the app never sends cleartext traffic.
Downloads are capped at 20 MB and 90 seconds, and `pass.json` at 1 MB.

English and Russian, light and dark theme (Material You colours on Android 12+).
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

## Adding a deeplink

Each supported link shape is a template in [`deeplinks.gradle.kts`](deeplinks.gradle.kts), with
`{url}` where the address of the `.pkpass` sits. Add one and rebuild:

```kotlin
extra["deeplinks"] = listOf(
    "walletunion://import/{url}",
    "passeswallet://import/{url}",
    "walletpasses://import/{url}",
    "walletpasses://import?url={url}",
    "newwallet://add/{url}",           // <- newwallet://add/https://host/card.pkpass
    "otherwallet://p?d={url:base64}",  // <- otherwallet://p?d=aHR0cHM6Ly9…
)
```

| `{url}` placement | What the app takes |
|---|---|
| `scheme://host/path/{url}` | the rest of the link; a URL percent-encoded as a whole (`https%3A%2F%2F…`) is decoded once |
| `scheme://host[/path]?name={url}` | the value of query parameter `name` |
| `{url:base64}` (either placement) | Base64, standard or URL-safe, padding optional |

The build generates one manifest intent-filter per template (scheme, host and path), so only
these exact shapes reach the app, and every build checks that the merged manifest still has
them. A link of one of these schemes that fits no template is rejected rather than guessed at.
The build fails on a malformed template or a reserved scheme (`https`, `content`, …).

A link that carries an ID instead of a URL (e.g. `newwallet://card/<id>`) can't be expressed as
a template; it would need code that knows how to turn the ID into a URL.

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
| `release.yml` | release-please and the signed release build (see below) |

CI runs on pushes to `main` and on pull requests. All actions are pinned by commit SHA;
Dependabot (`.github/dependabot.yml`) bumps them and the Gradle dependencies in weekly grouped PRs.

### Dependency verification

Every Gradle artifact (plugins, test libraries, their POMs) is checked against the SHA-256
sums in [`gradle/verification-metadata.xml`](gradle/verification-metadata.xml). Local builds
and PR CI only warn on a mismatch; CI on `main` and the signed release build are strict. After
merging a dependency update, regenerate the file and commit it:

```
./gradlew --write-verification-metadata sha256 ktlintCheck testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest printVersionName
```

## Releasing

Releases are cut by [release-please](https://github.com/googleapis/release-please) from
[Conventional Commits](https://www.conventionalcommits.org): `fix: …` → patch, `feat: …` → minor,
`feat!: …` → major. Other commits (`ci:`, `chore:`, `docs:` …) don't trigger a release.

1. release-please keeps a `chore(main): release x.y.z` PR open with the new
   [`version.txt`](version.txt) and `CHANGELOG.md`.
2. Merge it when you want to release. That creates a **draft** GitHub Release: not public,
   no tag yet. Merging is therefore not yet a release.
3. The `publish` job in `release.yml` runs in the `release` environment; with required
   reviewers it waits for your approval. It runs ktlint, the unit tests and lint on the release
   commit (release-please's PR gets no CI of its own), builds the APK, signs it,
   attaches `debloatpass-vX.Y.Z.apk`, `SHA256SUMS` and a provenance attestation, and only then
   publishes the draft (which creates the `vX.Y.Z` tag). Obtainium picks it up from there.
   If any step fails, the release stays a draft and nothing is public.

`versionName` is read from `version.txt`; `versionCode` is derived from it
(`1.2.3` → `10203`), so there is nothing to bump by hand. Before merging the release PR, add the
store changelog for the new versionCode (F-Droid / IzzyOnDroid read it):
`fastlane/metadata/android/{en-US,ru-RU}/changelogs/<versionCode>.txt`, at most 500 characters.

Repository settings this needs:

- Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests"
  (for the release PR).
- Settings → Environments → `release` → Required reviewers (yourself), so nothing is built or
  published without an explicit approval.
- The signing secrets below, as repository secrets or in the `release` environment.

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
