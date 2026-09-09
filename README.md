# Travian Farm Assistant v2.8

Android companion app for logging into a Travian account through an embedded WebView and loading the account Farm Lists automatically.

## v2.8 — GitHub-ready project structure

This release fixes the Gradle project layout so GitHub Actions can build `:app:assembleDebug` correctly. The Android module is now under `app/`, while the root project contains only Gradle/settings files.

### Automatic login and Farm List flow

1. Enter **Server**, **Username**, and **Password**.
2. Tap **LOGIN / REFRESH FARM LIST**.
3. The app opens the Travian server in the embedded WebView.
4. If a valid session already exists, it proceeds directly to the Farm List.
5. If login is required, the app attempts to fill the Travian login form and submit it automatically.
6. The app opens the current Farm List route: `build.php?id=39&gid=16&tt=99`.
7. Farm Lists are detected from the page and displayed as dynamic checkboxes.

### Consent handling

The WebView includes automatic handling for the ConsentManager cookie banner, including open Shadow DOM variants. Farm List parsing waits until the consent layer is dismissed.

### Project structure

```text
TravianFarmAssistant7/
├── .github/
│   └── workflows/
│       └── main.yml
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/travianfarmassistant/MainActivity.kt
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/
│               ├── strings.xml
│               └── themes.xml
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── README.md
```

### GitHub Actions

The repository includes a workflow that:

- checks out the repository;
- installs JDK 17;
- installs/configures the Android SDK;
- configures Gradle 8.9;
- verifies the expected Android project structure;
- builds `:app:assembleDebug`; and
- uploads `app-debug.apk` as a workflow artifact.

### Build locally

With Gradle 8.9 and JDK 17 available:

```bash
gradle --no-daemon --stacktrace :app:assembleDebug
```

The generated APK is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

> Note: Travian can change its login page or Farm List HTML. CAPTCHA/2FA or major HTML changes may require selector updates in `MainActivity.kt`.


## v2.8 changes
- Farm List parser follows the current React Farm List structure (`#rallyPointFarmList` / `.farmListWrapper`).
- Collapsed Farm Lists are expanded and scrolled into view to trigger lazy rendering.
- Added retries while the React Farm List is rendered.
- WebView is no longer inside the outer ScrollView, so the Travian page gets its own full scrollable area at the bottom of the app.
