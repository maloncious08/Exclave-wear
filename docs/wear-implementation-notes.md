# Wear OS Implementation Notes

## Added/Changed Files
- `gradle/libs.versions.toml`: Added Kotlin Compose Compiler plugin and AndroidX Wear Compose dependencies. Extracted kotlin version to `version.ref`.
- `buildSrc/src/main/kotlin/Helpers.kt`: Added `watch` flavor definition and handled its APK renaming and task ordering.
- `app/build.gradle.kts`: Added `compose = true` build feature, mapped `watch` sourceSets to reuse `oss`, configured Compose compiler and Wear OS dependencies for `watchImplementation`.
- `.github/workflows/wear-build.yml`: Cloned `debug.yml` to build the watch variant.
- `app/src/watch/AndroidManifest.xml`: Watch-specific manifest setting feature required, standalone, and launching `WearMainActivity` while removing the launcher intent from the main phone activity.
- `app/src/watch/java/io/nekohasekai/sagernet/wear/WearMainActivity.kt`: Contains all Compose-based watch UI code, replacing the monolithic phone UI functionality.

## Build and Debug Commands
- **Build debug APK for watch:** `./gradlew :app:assembleWatchDebug`
- **Import subscription via adb:** `adb shell am start -n io.nekohasekai.sagernet.wear/io.nekohasekai.sagernet.wear.WearMainActivity --es subscription_url <url>`

## Assumptions
- We reuse `app/src/oss` source files (which only contains `play/` assets currently) for the `watch` flavor to ensure it mirrors `oss` functionality, since `watch` flavor is based on `oss` implementations.
- No new UI dependencies were added for XML components. Wear compose replaces the whole interaction surface.
- SagerNet core relies heavily on context and theming for its `GroupInterfaceAdapter`. Thus, a stripped-down `GroupManager.Interface` stub was used in `WearMainActivity` to simply log the update results and bypass the `MaterialAlertDialogBuilder` used in the phone app which crashed on non-AppCompat themes.
- SagerConnection can be cleanly controlled directly via `SagerNet.startService()` and `SagerNet.stopService()`, relying on its pre-existing VPN permission mechanisms (`VpnService` requests its own if needed).

## Notification Tap Target
- The notification tap target remains as it is. It opens the phone's `MainActivity`. On Wear OS, this is handled gracefully by the system (usually trying to launch the activity if it is present; though we removed `LAUNCHER`, it is still exported and can handle Intents like those from the notification).

## Missing / Limitations
- As instructed, CI and test pipelines for the UI components were omitted beyond adding the workflow file since this targets a device class not testable via standard command-line tools without emulators in CI.
- The `GroupManager.userInterface` is only bound while `WearMainActivity` is alive; thus, background group updates won't display popups.
