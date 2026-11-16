# DebloatPass

[![build](https://github.com/kinjalik/debloatpass/actions/workflows/build.yml/badge.svg)](https://github.com/kinjalik/debloatpass/actions/workflows/build.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/kinjalik/debloatpass/badge)](https://scorecard.dev/viewer/?uri=github.com/kinjalik/debloatpass)

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/kinjalik/debloatpass"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54"></a>

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

## Development

Build, tests, code style, CI and dependency verification: [DEVELOPMENT.md](DEVELOPMENT.md).
Releasing: [docs/how-to-make-release.md](docs/how-to-make-release.md).

## License

[MIT](LICENSE)
