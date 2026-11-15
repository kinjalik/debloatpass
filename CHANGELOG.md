# Changelog

## 1.0.0 (2026-11-15)


### ⚠ BREAKING CHANGES

* deeplinks are explicit templates (scheme://host/path/{url}, ?name={url}, {url:base64})

### Features

* deeplinks are explicit templates (scheme://host/path/{url}, ?name={url}, {url:base64}) ([aa11b54](https://github.com/kinjalik/debloatpass/commit/aa11b549c4457951000e150935ce0655bf285479))
* first public release ([eb85708](https://github.com/kinjalik/debloatpass/commit/eb857083195b0763fe771ef738330df9d11943d2))
* Material You colors on Android 12+ ([af0bb40](https://github.com/kinjalik/debloatpass/commit/af0bb40a3aa517c17920b900500b29ebb77cc40f))
* new main screen text ([e30c247](https://github.com/kinjalik/debloatpass/commit/e30c247bacffca1cbb8b92626706d631dc77ccfd))
* no-wallet text links Google Wallet, or explains its pkpass import is unavailable ([41bf7c5](https://github.com/kinjalik/debloatpass/commit/41bf7c52fbdeae2c3fe4f34b1a2d829a9af1a9d1))
* warn when no app can open .pkpass ([91c4b13](https://github.com/kinjalik/debloatpass/commit/91c4b13419d6f360b700eecd21aaa79484b3e18c))


### Bug Fixes

* content no longer hides under the action bar on Android 15+ ([e3f1016](https://github.com/kinjalik/debloatpass/commit/e3f101683bc036112eda10502f85feca3ccc18b0))
* deeplink filter missing from the APK after a clean build ([e8e9cda](https://github.com/kinjalik/debloatpass/commit/e8e9cdab487a511cb5ba2e4e62aba1bd497ed0dc))
* own task for ImportActivity (taskAffinity="") ([2a7248d](https://github.com/kinjalik/debloatpass/commit/2a7248d522dd1f54cb4f9b396ccdff2379be183e))
* recognize shared links with a broken slash (https:/host) ([0f83ab8](https://github.com/kinjalik/debloatpass/commit/0f83ab8bd5f389c384f7cab6acd73d7d11403f40))
* review findings (downloads, state, wallet detection, errors, dark theme) ([18e1f94](https://github.com/kinjalik/debloatpass/commit/18e1f9426863cb39977238d2a801e415d8befb54))
* second review findings ([9b8c280](https://github.com/kinjalik/debloatpass/commit/9b8c2805271e44972b9e9ce2e5b8bd1a4612d707))
* squashed toolbar and dark title on Samsung (Android 17) ([d8182aa](https://github.com/kinjalik/debloatpass/commit/d8182aa756134ceaadf2c839b43d07041cd903cc))
