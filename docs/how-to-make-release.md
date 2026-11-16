# How to make a release

Releases are cut by [release-please](https://github.com/googleapis/release-please) from
[Conventional Commits](https://www.conventionalcommits.org). Example below: `1.0.1`.

## Steps

1. **Have a commit that bumps the version** on `main`:
   - `fix: …` → patch (`1.0.0` → `1.0.1`), `feat: …` → minor, `feat!: …` → major;
   - `ci:`, `docs:`, `chore:`, `build:`, `test:` don't trigger a release;
   - to release anyway, or to force a version, push an empty commit with a `Release-As` footer:

     ```bash
     git commit --allow-empty -m "chore: release 1.0.1" -m "Release-As: 1.0.1"
     ```

2. **Add the store changelog** for the new versionCode (`x.y.z` → `x*10000 + y*100 + z`, so
   `1.0.1` → `10001`), at most 500 characters each, and push it to `main`:

   ```
   fastlane/metadata/android/en-US/changelogs/10001.txt
   fastlane/metadata/android/ru-RU/changelogs/10001.txt
   ```

3. **Check the release PR.** release-please opens (or updates) `chore(main): release 1.0.1`.
   Read `CHANGELOG.md` in it; `version.txt` must say `1.0.1`.

4. **Squash and merge the PR.** That creates a **draft** release and the `v1.0.1` tag; nothing
   is public yet.

5. **Wait for the `release` workflow** (Actions → release, ~10 min). It:
   1. waits for `build` and `emulator` to pass on the release commit;
   2. runs ktlint, unit tests and lint once more;
   3. waits for your approval, if the `release` environment has required reviewers;
   4. builds and signs the APK, attaches `debloatpass-v1.0.1.apk`, `SHA256SUMS` and a provenance
      attestation;
   5. publishes the release.

6. **Check the release**: it is marked Latest, has the APK attached, and
   `gh attestation verify debloatpass-v1.0.1.apk -R kinjalik/debloatpass` passes.
   Obtainium picks it up by itself.

## If it fails

The release stays a draft; nothing is public.

1. Fix the cause. If it was a flaky `build` / `emulator` run on the release commit, re-run it
   until it's green.
2. Actions → release → **Run workflow**, branch `main`, tag `v1.0.1`.

Don't re-run the original `release` run: release-please reports a new release only once, so
the re-run skips publishing (and may open a duplicate release PR).

Never merge a second release PR for a version that already has a draft: close it.

## One-time setup

- Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests".
- Settings → Environments → `release`:
  - deployment branches: `main` (the workflow runs on the push of the merge, not on a tag);
  - required reviewers: yourself, if every release should wait for an explicit approval.
- Tag ruleset for `v*`: may block deletion, updates and force pushes, but **not creation**
  (the workflow token creates the tag and can't bypass rulesets).
- Secrets (repository or `release` environment):

  | Secret | Value |
  |---|---|
  | `KEYSTORE_BASE64` | `base64 -w0 debloatpass-release.jks` |
  | `SIGNING_STORE_PASSWORD` | from `keystore.properties` |
  | `SIGNING_KEY_ALIAS` | `debloatpass` |
  | `SIGNING_KEY_PASSWORD` | from `keystore.properties` |
