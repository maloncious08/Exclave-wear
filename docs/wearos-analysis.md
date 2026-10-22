# Wear OS Module Analysis

This document analyzes the existing Exclave codebase to assess the feasibility and required architecture for adding a standalone Wear OS module.

## 1. Project Structure

**Gradle Modules:**
- `:library:proto`
- `:library:proto-stub`
- `:plugin:api`
- `:plugin:naive`
- `:app`

**SDK Versions (from `buildSrc/src/main/kotlin/Helpers.kt` and `app/build.gradle.kts`):**
- `compileSdk`: 37
- `targetSdk`: 37
- `minSdk`: Default flavor (`oss`) uses 24, legacy flavor (`legacy`) uses 21.

**Build Flavors:**
- Dimension: `vendor`
- Flavors: `oss` (default) and `legacy`

**UI Framework:**
- Currently uses traditional Android XML Views and Fragments/Activities (`androidx.appcompat`, `androidx.fragment`, ViewBinding is enabled). No Jetpack Compose is currently used in the main app.

**Key UI Dependencies:**
- AndroidX (Core, Activity, Fragment, AppCompat, Room, Work, Camera, SwipeRefreshLayout)
- Google Material (`com.google.android.material:material`)
- Markwon (markdown parsing)
- Preference (`androidx.preference:preference`)
- RecyclerView FastScroll

## 2. Entry Points

**Main Activity:**
- `io.nekohasekai.sagernet.ui.MainActivity` (Parent Activity containing toolbars and fragments)

**Services:**
- `io.nekohasekai.sagernet.bg.ProxyService`: General proxy service handling connection processes (`specialUse` foreground service).
- `io.nekohasekai.sagernet.bg.VpnService`: Connects as an Android VPN (`BIND_VPN_SERVICE`).

**Connection & State Lifecycle:**
- Start/Stop calls are routed through `io.nekohasekai.sagernet.SagerNet.startService()` / `stopService()` which starts/stops the appropriate service based on `DataStore.serviceMode` (VPN or Proxy).
- The state is tracked and observed via AIDL using `io.nekohasekai.sagernet.aidl.ISagerNetService` (bound to from `io.nekohasekai.sagernet.bg.SagerConnection`).
- UI registers to `SagerConnection` (which wraps the Binder interface) and implements `SagerConnection.Callback` to react to state changes (`stateChanged(state, profileName, msg)`).
- Quick toggle tile is managed in `io.nekohasekai.sagernet.bg.TileService`.

## 3. Profile/Server Storage

**Storage Mechanism:**
- Profiles, groups, and rules are persisted using an AndroidX Room database (`io.nekohasekai.sagernet.database.SagerDatabase`).
- App settings are persisted using `io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore` backed by Key-Value pairs in Room database (`PublicDatabase` and `InMemoryDatabase`), heavily accessed via the singleton `io.nekohasekai.sagernet.database.DataStore`.

**Classes Managing Reading/Selection:**
- `DataStore.currentProfile` / `DataStore.selectedProxy` hold the currently selected profile IDs.
- Profile parsing/addition uses `io.nekohasekai.sagernet.database.ProfileManager` (and formats in `io.nekohasekai.sagernet.fmt.*`).
- Group imports use `io.nekohasekai.sagernet.database.GroupManager` and various updater classes (e.g., `io.nekohasekai.sagernet.group.GroupUpdater`).

## 4. Native Core (`libexclavecore`)

**Bundling & Loading:**
- The Go core is built via `gomobile bind` (see `library/core/build.sh`).
- It outputs an AAR file (`libexclavecore.aar`) which is copied into `app/libs`.
- The ABIs supported are configured in `setupAppCommon` (x86, x86_64, armeabi-v7a, arm64-v8a).
- It is statically included in the app via `implementation(fileTree("libs"))`.
- To use it in a new Wear OS module, the `.aar` file dependency must be exposed either by duplicating the `libs` folder dependency, or better, creating a dedicated `core` Gradle module that wraps the `.aar` and exposes it via `api` to both `:app` and the new `:wear` module.

## 5. Coupling Assessment

The `:app` module is currently highly monolithic:
- UI, background services (`bg.*`), data access (`database.*`), format parsers (`fmt.*`), and native execution logic (`bg.proto.*`) are all tightly coupled inside `:app`.
- Starting the VPN is intimately bound to Android intents and `app` context assumptions.
- Logic like `SagerConnection`, `BaseService`, `DataStore`, and `SagerDatabase` are embedded in the app.
- A new Wear module cannot simply depend on `:app` because an Android Application module cannot be a dependency of another Application module.
- *Conclusion*: A significant extraction effort is required. All core logic (Database, DataStore, Services, Format Parsers, Native Runners) must be extracted into a shared Android Library module (e.g., `:core-app` or `:shared`), leaving only UI (`io.nekohasekai.sagernet.ui.*`) in the `:app` module.

## 6. Wear OS Blockers

- **VPN Limitations:** While Wear OS 3+ technically supports `VpnService`, configuring it and obtaining user consent on small screens may require special intent handling or direct user approval. Sometimes the OS restricts VPNs to save battery.
- **UI Architecture:** The heavy reliance on AppCompat, Fragments, XML layouts, Camera X (QR code scanning), and `com.google.android.material` components are not suitable for Wear OS. Wear OS requires `androidx.wear.compose` or specialized Wear XML layouts.
- **Scanner Activity:** `ScannerActivity` (using camera X) would be useless and difficult to support on watches without cameras.
- **Foreground Service Limitations:** Wear OS is extremely aggressive in killing background processes to save battery. Keeping a VPN/Proxy running might result in fast battery drain and system interference.
- **Data Entry:** Manually adding configuration URLs or massive keys via a watch keyboard is impossible. Profile sync via the Data Layer API (Phone <-> Watch) will be required.

## 7. Proposed Minimal Design for Wear OS Module

1. **Refactor & Extract (`:shared` module):**
   - Create a new Android Library module (`:shared`).
   - Move `io.nekohasekai.sagernet.bg.*` (Services, connection management, core runners).
   - Move `io.nekohasekai.sagernet.database.*` (Room DB, DataStore, models).
   - Move `io.nekohasekai.sagernet.fmt.*` (Profile formats).
   - Move the `libs/libexclavecore.aar` dependency into `:shared`.
   - Update `:app` to depend on `:shared`.
2. **Create Wear OS Module (`:wear`):**
   - Create a new Android Application module for Wear OS.
   - Set dependency to `implementation(project(":shared"))`.
3. **Wear UI (Compose for Wear OS):**
   - Use `androidx.wear.compose:compose-material` for the UI.
   - Implement a simple dashboard: Server list, Start/Stop toggle button, Traffic stats.
   - Connect UI to the existing `SagerConnection` and `DataStore` (from `:shared`) to start/stop the service exactly as the mobile app does.
4. **Data Sync (Phone to Watch):**
   - Add Wearable Data Layer API (`play-services-wearable`) to both `:app` and `:wear`.
   - When a profile is added/modified on the phone, sync the configuration payload to the watch so the watch's Room database stays up to date.
