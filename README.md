# Browse My Phone

A simple Android file explorer built with Kotlin and Jetpack Compose.

## Features

- **Front page titled "Browse My Phone"** with quick category buttons at the top:
  **Images**, **Audio**, **Video**, **Big files**.
- **Storage view** showing every mounted volume — internal storage and any SD card / USB —
  with a usage bar (used / total / free). Tap a volume to browse it.
- **File browser** — tap folders to go deeper, use the back arrow to go up, tap a file to open
  it in whatever app can handle it.
- **Images / Audio / Video** categories pull from the system MediaStore (newest first).
- **Big files** scans all storage for files larger than 100 MB, largest first — handy for
  freeing up space.

## Requirements

- Android Studio 2026.1 (or newer) — bundles the JDK (25) and Gradle it needs
- An Android device or emulator running **Android 8.0 (API 26)** or later

Toolchain this project targets:

| Piece       | Version   |
|-------------|-----------|
| compileSdk / targetSdk | 36 (Android 16) |
| minSdk      | 26 (Android 8.0) |
| Android Gradle Plugin | 9.0.0 |
| Gradle      | 9.4.0 |
| Kotlin      | 2.3.10 |

## Open & run

1. In Android Studio: **File → Open** and select this `BrowseMyPhone` folder.
2. Let Gradle sync finish. On this fresh machine it will need to download a few things:
   - The Gradle 9.4.0 distribution and the Android Gradle Plugin + Compose libraries.
   - **SDK Platform 36** — Studio shows a one-click *"Install missing SDK package(s)"* link
     in the sync panel; accept it.
3. Pick a device/emulator and press **Run** ▶.

> **Version note:** this machine's Android Studio is newer than usual, so it bundles JDK 25 —
> which needs the Gradle 9.x / AGP 9.x line pinned above. If the first sync says a specific
> version can't be found, use Studio's **AGP Upgrade Assistant** (or the sync quick-fix) to snap
> to the exact installed version. The `gradle-wrapper.jar` isn't committed; Studio regenerates
> the wrapper on first sync (you don't need `./gradlew` to build from the IDE).

## Permissions

To list real files and folders the app needs broad storage access:

- **Android 11+ (API 30+):** "All files access" (`MANAGE_EXTERNAL_STORAGE`). The app sends you
  to the system settings screen to enable it; the home screen shows a **Grant access** banner
  until it's on.
- **Android 8–10:** the standard `READ_EXTERNAL_STORAGE` runtime prompt.

The storage-usage bars work without any permission; browsing and the categories need it.

## Project layout

```
app/src/main/java/com/example/browsemyphone/
├── MainActivity.kt      # UI, navigation, permission handling (Compose)
├── FileRepository.kt    # storage stats, directory listing, MediaStore, big-file scan, open
└── ui/theme/Theme.kt    # Material 3 theme
```
