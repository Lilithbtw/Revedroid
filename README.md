# Revedroid

Revedroid is an Android client for [Cloudreve](https://cloudreve.org) v4, built with Kotlin, Jetpack Compose and Material 3. It lets you sign in to your Cloudreve server from your phone and manage your files: browse folders, upload and download, share links, and empty the trash.

## Why it exists

Many Cloudreve servers sit behind Cloudflare, and Cloudflare often blocks apps that don't behave like a browser. Revedroid handles that case. When Cloudflare asks for a check, the app shows it in a WebView, then keeps the resulting session, so you don't repeat the check every time.

## Download

Download the latest APK from the [releases tab](https://github.com/Lilithbtw/Revedroid/releases), then install it on your Android device. You may need to allow installs from your browser or file manager first.

Release APKs are signed with the project's release key, so an update installs over an earlier release. Debug builds are signed with a different key and need an uninstall first.

## Features

- Sign in with your password, including Cloudreve's login captchas (image, reCAPTCHA, Turnstile, and Cap) and two-factor codes.
- Tokens are stored encrypted with an Android Keystore AES-GCM key and refreshed before they expire.
- Browse files as a list or a grid, with thumbnails, breadcrumbs, sorting, search, pull-to-refresh, and pagination.
- Create folders, rename items, move them to the trash, create share links, and download files through the system DownloadManager.
- Restore items from the trash, delete them permanently, or empty the trash.
- Upload in chunks to `local` and `remote` (slave node) storage policies, and to any policy running in relay mode.
- See your storage usage, choose a theme, use Material You dynamic color, sign out, or switch servers from the account page.

## How connecting works

Enter your server address. If you leave out the scheme, Revedroid assumes HTTPS. If you paste a full `/api/v4` URL, the suffix is removed for you, and installs that live under a sub-path keep their path.

The app then checks that the server is reachable:

- If there is no Cloudflare challenge, the app connects right away.
- If Cloudflare shows a challenge, the app opens it in a WebView so you can pass the check. It then tries again, with backoff, until the server responds.
- If Cloudflare blocks the request outright (error 1020, for example), the app tells you so. Retrying won't help in that case.

Once the server is reachable, the app signs you in, or takes you straight to your files if it already has a valid session for that server.

The Cloudflare clearance cookie is stored in the WebView's cookie manager and shared with the app's HTTP client, so it survives restarts. Requests also use the WebView's User-Agent, because Cloudflare ties clearance to it. If the clearance expires during use, Revedroid shows the verification screen again and refreshes the current view afterwards.

## Not supported yet

- Direct uploads to third-party object storage (S3, OSS, COS, OneDrive, and similar) when relay mode is off.
- Passkey, SSO, and OAuth sign-in, registration, and password reset.
- Moving and copying files, previews and viewers, share management, WebDAV, and offline downloads.
- Uploads run in the ViewModel, so they stop if the app process is killed. WorkManager is the planned fix.

## Compile from source

Requirements: JDK 17 and the Android SDK with compileSdk 35. The minimum supported Android version is 29 (Android 10).

```sh
git clone git@github.com:Lilithbtw/Revedroid.git
cd Revedroid
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. You can also open the project in Android Studio.

To run the unit tests (URL handling, ping, and Cloudflare detection):

```sh
./gradlew :app:testDebugUnitTest
```

### Release builds

`./gradlew :app:assembleRelease` signs the APK at `app/build/outputs/apk/release/app-release.apk`. Signing values come from a local `keystore.properties` file, which looks like this:

```properties
storeFile=revedroid-release.jks
storePassword=…
keyAlias=revedroid
keyPassword=…
```

Neither the keystore nor this file is committed, and `.gitignore` excludes both. You can also set `REVEDROID_KEYSTORE_PATH`, `REVEDROID_KEYSTORE_PASSWORD`, `REVEDROID_KEY_ALIAS`, and `REVEDROID_KEY_PASSWORD` as environment variables. If neither source is set, the release APK is built unsigned.

Back up the keystore. Android only installs updates signed with the same key, so losing it means existing users have to uninstall the app before they can upgrade.

## Code style

Kotlin is checked with [ktlint](https://github.com/pinterest/ktlint), using the `ktlint_official` style defined in `.editorconfig`:

```sh
./gradlew ktlintCheck     # report problems
./gradlew ktlintFormat    # fix what can be fixed automatically
```

## Continuous integration

| Workflow | Runs on | Purpose |
|---|---|---|
| `ci.yml` | pushes to `main`, pull requests | ktlint, Android lint (results go to code scanning), unit tests, and a debug APK artifact |
| `codeql.yml` | pushes to `main`, pull requests, weekly | CodeQL `security-extended` analysis of the Kotlin code |
| `release.yml` | tags matching `v*` | Builds the signed release APK and attaches it to a GitHub release |
| `dependabot-auto-merge.yml` | Dependabot pull requests | Enables auto-merge for patch and minor updates |

Dependabot checks Gradle dependencies, including `gradle/libs.versions.toml`, and GitHub Actions each week. Related libraries are grouped, so Kotlin and its compiler plugins update together.

To set up a fork or a new repository:

1. Add the secrets `REVEDROID_KEYSTORE_BASE64` (the output of `base64 -w0 revedroid-release.jks`), `REVEDROID_KEYSTORE_PASSWORD`, `REVEDROID_KEY_ALIAS`, and `REVEDROID_KEY_PASSWORD` under Settings, then Secrets and variables, then Actions.
2. Turn on "Allow auto-merge" under Settings, then General. Add a branch ruleset on `main` that requires the `ktlint`, `Android lint, tests & build`, and `Security analysis (Kotlin)` checks. Dependabot pull requests then merge only after those pass. Major version bumps are never merged automatically.
3. Code scanning results appear under Security, then Code scanning. On private repositories, this needs GitHub Advanced Security.

The ktlint engine version (`ktlintEngine` in the version catalog) is not a normal dependency, so Dependabot doesn't update it. Update it by hand when the Gradle plugin changes.

## Project layout

```
data/api     Retrofit interface and models for /api/v4 (envelope: code, data, msg)
data/net     Cookie jar shared with the WebView, Cloudflare detection, auth and token refresh
data/repo    Ping, auth, files and uploads, downloads
data/store   DataStore settings and the encrypted session
ui/connect   Server entry and the ping and challenge state machine
ui/challenge Cloudflare WebView
ui/login     Sign-in, two-factor, and captcha widgets
ui/files     File browser, sheets, and dialogs
ui/account   Profile, storage, and appearance
```
