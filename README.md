# Revedroid

A native Android client for [Cloudreve](https://cloudreve.org) v4, written in Kotlin with Jetpack Compose and Material 3. It works whether or not the server sits behind Cloudflare.

## Connection flow

1. The user enters the server address. HTTPS is assumed when no scheme is given, a pasted `/api/v4` is stripped, and sub-path installs are kept.
2. The app sends `GET /api/v4/site/ping`.
   - **No Cloudflare:** an HTTP 200 with `{"code":0}` connects straight away.
   - **Cloudflare challenge:** the response is recognised (`cf-mitigated: challenge`, or a Cloudflare 403/429/503 HTML page with challenge markers). The app then opens the ping URL in a WebView so the user can pass the check.
   - **Cloudflare firewall block (e.g. 1020):** reported as such. Retrying won't help.
3. Once the challenge is passed, the app pings again, with backoff, until it gets HTTP 200 and `code: 0`.
4. The app signs in, or goes straight to the files if it already holds a valid session for that server.

The Cloudflare session is kept because OkHttp's cookie jar is backed by the WebView `CookieManager`. The `cf_clearance` cookie the WebView earns is sent on every API, thumbnail and download request, and it survives app restarts. Every request also carries the WebView's exact User-Agent, because Cloudflare ties clearance to it. If clearance expires mid-session, any API call that hits a challenge reopens the verification screen and refreshes the current view afterwards.

## Features

- Password sign-in with Cloudreve's own login captchas (image, reCAPTCHA, Turnstile, Cap) and two-factor codes
- Access/refresh tokens stored with an Android Keystore AES-GCM key, refreshed automatically before they expire
- File browser with list and grid layouts, thumbnails, breadcrumbs, sorting, search, pull-to-refresh and pagination
- New folder, rename, move to trash, share link, download through the system DownloadManager
- Trash: restore, delete forever, empty
- Chunked uploads for `local` and `remote` (slave node) storage policies, and for any policy in relay mode
- Account page with storage usage, theme selection, Material You dynamic color, sign-out and server switching

## Not covered yet

- Direct uploads to third-party object storage (S3, OSS, COS, OneDrive, …) when relay is off
- Passkey/SSO/OAuth sign-in, registration and password reset
- Moving and copying files, previews and viewers, share management, WebDAV, offline downloads
- Uploads run in the ViewModel and stop if the app process is killed. WorkManager would be the next step.

## Building

Requires JDK 17 and the Android SDK (compileSdk 35). minSdk is 29.

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # URL handling, ping and Cloudflare detection tests
```

Or open the project in Android Studio.

### Release signing

`./gradlew :app:assembleRelease` signs `app/build/outputs/apk/release/app-release.apk` with the key described in `keystore.properties`:

```properties
storeFile=revedroid-release.jks
storePassword=…
keyAlias=revedroid
keyPassword=…
```

Neither file is committed (see `.gitignore`). Back them up. Android only installs updates signed with the same key, so losing it means users must uninstall to upgrade. Without `keystore.properties`, the same values are read from the `REVEDROID_KEYSTORE_PATH`, `REVEDROID_KEYSTORE_PASSWORD`, `REVEDROID_KEY_ALIAS` and `REVEDROID_KEY_PASSWORD` environment variables. If neither is set, the release APK is built unsigned.

### Code style

Kotlin style is enforced with [ktlint](https://github.com/pinterest/ktlint) (`ktlint_official` style, configured in `.editorconfig`):

```sh
./gradlew ktlintCheck    # report
./gradlew ktlintFormat   # fix what can be fixed automatically
```

## GitHub automation

| Workflow | Runs on | What it does |
|---|---|---|
| `ci.yml` | push to `main`, PRs | ktlint; Android lint (results go to code scanning); unit tests; debug APK artifact |
| `codeql.yml` | push to `main`, PRs, weekly | CodeQL `security-extended` analysis of the Kotlin code |
| `release.yml` | tags `v*` | builds the signed release APK and attaches it to a GitHub release |
| `dependabot-auto-merge.yml` | Dependabot PRs | enables auto-merge for patch/minor updates |

`.github/dependabot.yml` checks Gradle dependencies (including `gradle/libs.versions.toml`) and GitHub Actions weekly. Related libraries are grouped so that, for example, Kotlin and its compiler plugins update together.

One-time repository setup:

1. **Secrets** (Settings → Secrets and variables → Actions):
   - `REVEDROID_KEYSTORE_BASE64`: output of `base64 -w0 revedroid-release.jks`
   - `REVEDROID_KEYSTORE_PASSWORD`, `REVEDROID_KEY_ALIAS`, `REVEDROID_KEY_PASSWORD`: values from `keystore.properties`
2. **Auto-merge**: enable "Allow auto-merge" (Settings → General). Add a branch ruleset on `main` that requires the `ktlint`, `Android lint, tests & build` and `Security analysis (Kotlin)` checks, so Dependabot PRs merge only after they pass. Major version bumps are never auto-merged.
3. **Code scanning** results appear under Security → Code scanning. On private repos this requires GitHub Advanced Security.

The ktlint engine version (`ktlintEngine` in the version catalog) isn't a regular dependency, so Dependabot won't bump it. Update it by hand when the Gradle plugin moves on.

## Layout

```
data/api     Retrofit interface + models for /api/v4 (envelope: code/data/msg)
data/net     Cookie jar shared with WebView, Cloudflare detection, auth + token refresh
data/repo    Ping, auth, files/uploads, downloads
data/store   DataStore settings and encrypted session
ui/connect   Server entry + ping/challenge state machine
ui/challenge Cloudflare WebView
ui/login     Sign-in, 2FA, captcha widgets
ui/files     Browser, sheets, dialogs
ui/account   Profile, storage, appearance
```
