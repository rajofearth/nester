# nester-mobile

Kotlin + Jetpack Compose Android app for nester.

## Build on this machine

1. Copy `local.properties.example` to `local.properties` (SDK path is already set).
2. Generate the Gradle wrapper once (no wrapper is committed):
   `gradle wrapper --gradle-version 9.3.1` — or just open this folder in Android Studio, which generates it.
3. Build: `.\gradlew.bat assembleDebug`
4. No emulator on this PC. Install on a physical device over adb:
   `.\gradlew.bat installDebug` (device connected with USB debugging enabled)
