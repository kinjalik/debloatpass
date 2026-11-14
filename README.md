# DebloatPass

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
- `app/src/sharedTest` — the tiny HTTP server and pkpass fixtures used by both.

CI runs the unit tests in `build.yml` and the instrumented tests on an emulator (API 24 and 34)
in `emulator.yml`. The test libraries are test-only; the app itself still has no dependencies.

## Releasing

1. Bump `appVersionName` / `appVersionCode` in [`app/build.gradle.kts`](app/build.gradle.kts).
2. Commit, then tag `v<versionName>` and push the tag:
   `git tag v1.0.0 && git push origin v1.0.0`
3. CI checks that the tag matches `versionName`, builds and signs the APK, and publishes a
   GitHub Release with `debloatpass-v1.0.0.apk`. Obtainium picks it up from there.

CI needs these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 debloatpass-release.jks` |
| `SIGNING_STORE_PASSWORD` | from `keystore.properties` |
| `SIGNING_KEY_ALIAS` | `debloatpass` |
| `SIGNING_KEY_PASSWORD` | from `keystore.properties` |
