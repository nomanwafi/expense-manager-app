# Expense Manager — Android WebView Wrapper

A production-ready Kotlin Android app that wraps **https://expense.nomananik.com/** inside a
secure WebView. No frontend/backend/business logic from your website is touched — this is a
native shell only.

## ⚠️ About the APK

This project was generated in a sandboxed environment **without Android SDK / build-tools
access**, so I could not compile the `.apk` myself here. Below are three ways to get the actual
APK — pick whichever is easiest for you. Option A takes about 5 minutes and needs nothing
installed on your computer.

### Option A — Build it in the cloud with GitHub Actions (no install needed, ~5 min)
1. Create a new **empty** GitHub repository.
2. Upload the entire contents of this project folder to that repo (drag-and-drop on
   github.com works, or `git push`).
3. Go to the repo's **Actions** tab → the "Build APK" workflow runs automatically on push
   (or click **Run workflow** to trigger it manually).
4. When it finishes (green check), open the workflow run → **Artifacts** →
   download `expense-manager-debug-apk`. Unzip it — that's your installable `.apk`.

This produces a **debug** APK, which is perfectly installable on any Android phone
(Settings → allow "Install unknown apps" for your file manager/browser). For a **release**
APK signed with your own key, see Option C.

### Option B — Build in Android Studio (recommended for ongoing development)
1. Install [Android Studio](https://developer.android.com/studio) (latest stable).
2. **Open** this project folder in Android Studio (File → Open).
3. Let Gradle sync finish (it will download the Android SDK bits it needs automatically).
4. Click **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. Find the APK at `app/build/outputs/apk/debug/app-debug.apk`.
6. To install directly on a plugged-in phone, just click the green **Run ▶** button instead.

### Option C — Build a signed release APK from the command line
```bash
# 1. Generate your own signing key (only once — keep this file safe!)
keytool -genkeypair -v -keystore release.keystore -alias expensemanager \
  -keyalg RSA -keysize 2048 -validity 10000

# 2. Put release.keystore inside the app/ folder, then uncomment the
#    signingConfigs.release block in app/build.gradle and fill in your
#    passwords/alias (and uncomment `signingConfig signingConfigs.release`
#    in the release buildType).

# 3. Build
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## What's included

| Requirement | Implementation |
|---|---|
| Kotlin, min SDK 24, Material 3 | `app/build.gradle`, `themes.xml` |
| Secure WebView (JS, DOM storage, cookies, session persistence, hardware accel) | `MainActivity.kt` → `setupWebView()` |
| Splash screen (logo, fade animation, 2s delay) | `SplashActivity.kt`, `activity_splash.xml`, `anim/fade_in.xml` |
| File upload (PDF/image, camera, gallery, docs, multi-select) | `MainActivity.kt` → `onShowFileChooser`, `createCameraCaptureIntent` |
| File download via DownloadManager, saved to Downloads, notification, tap-to-open | `MainActivity.kt` → `startDownload`, `handleDownloadComplete` |
| Runtime permissions (camera, notifications, legacy storage) requested only when needed | Activity Result API launchers in `MainActivity.kt` |
| Back button → WebView history, else Exit App? dialog | `setupBackPress()` |
| No internet screen + Retry | `layoutNoInternet` in `activity_main.xml` + `isNetworkAvailable()` |
| Security: no cleartext traffic, mixed content blocked, WebView file access disabled, debugging off in release | `network_security_config.xml`, `setupWebView()`, `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` |

## Before you ship this

1. **Replace the placeholder app icon / splash logo.** I generated a simple wallet-style
   placeholder icon (`app/src/main/res/mipmap-*/ic_launcher*.png` and
   `drawable/logo_placeholder.png`) with Python/Pillow since I don't have your real logo.
   Swap these for your actual brand assets (Android Studio's Image Asset tool —
   right-click `res` → New → Image Asset — makes this a 2-minute job).
2. **Add your own release keystore** before publishing to the Play Store (see Option C above).
   Never commit `release.keystore` or passwords to a public repo — `.gitignore` already
   excludes it.
3. Double-check `web_url` in `app/src/main/res/values/strings.xml` if your domain ever changes.

## Project structure
```
ExpenseManager/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nomananik/expensemanager/
│       │   ├── ExpenseManagerApp.kt   (notification channel setup)
│       │   ├── SplashActivity.kt
│       │   └── MainActivity.kt        (all WebView/upload/download logic)
│       └── res/                       (layouts, drawables, icons, strings, themes)
├── .github/workflows/build-apk.yml    (cloud APK builder)
├── build.gradle
├── settings.gradle
└── gradle.properties
```
