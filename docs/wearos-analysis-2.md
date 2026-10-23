# Wear OS Module Analysis 2: Product Flavor Approach

This document evaluates the feasibility of adding a "watch" product flavor to the existing `:app` module without extracting a shared library.

## 1. Gradle

**Current Configuration:**
- **Flavors**: Dimension `vendor` with flavors `oss` (default, minSdk 24) and `legacy` (minSdk 21).
- **Signing**: Configured in `app/build.gradle.kts` using `release.keystore` and credentials from `local.properties`.
- **ApplicationId**: `io.nekohasekai.sagernet`.
- **BuildFeatures**: `aidl = true`, `buildConfig = true`, `viewBinding = true` (Compose is **not** configured).
- **KSP/Room/AIDL**: AIDL is true. KSP arguments `room.incremental` and `room.schemaLocation` are set.
- **Versions**: Kotlin `2.4.21`, AGP `9.4.1`, Gradle `9.8.2` (via wrapper).
- **Dependency Management**: Gradle Version Catalog (`gradle/libs.versions.toml`).

**What Must Change for a "watch" Flavor:**
- In `buildSrc/src/main/kotlin/Helpers.kt` (or `app/build.gradle.kts`): Add `productFlavors.create("watch")`.
- In `app/build.gradle.kts`:
  - Set `applicationIdSuffix = ".watch"` for the watch flavor.
  - Enable Compose for the watch flavor (`buildFeatures.compose = true`).
  - Add specific dependencies using `watchImplementation` (e.g., `androidx.wear.compose:compose-material`) and `kspWatch` for Room.

**Places that Branch on Flavor Names/Tasks:**
- `buildSrc/src/main/kotlin/Helpers.kt`: Defines `oss`/`legacy`, sets minSdk, renames output APK files based on `-oss`.
- `app/build.gradle.kts`: Defines `ossRelease` and `legacyRelease` in `aboutLibraries` exports. Uses `ossImplementation`, `legacyImplementation`, `kspOss`, `kspLegacy`.
- `.github/workflows/debug.yml`: Runs `./gradlew :app:downloadAssets` and `./gradlew :app:assembleOssDebug :app:assembleLegacyDebug`.
- `.github/workflows/release.yml`: Runs `./gradlew :app:downloadAssets` and `./gradlew :app:assembleOssRelease :app:assembleLegacyRelease`.
- `.github/workflows/release_naive.yml`: Runs `./gradlew :plugin:naive:assembleOssRelease`.
- `fastlane/metadata`: N/A (not present in repo).

## 2. Manifest

**Current Elements in `app/src/main/AndroidManifest.xml`:**
- **Permissions (14+)**: e.g., `INTERNET`, `FOREGROUND_SERVICE`, `BIND_VPN_SERVICE`, `POST_NOTIFICATIONS`, `CAMERA`.
- **Activities (40)**: `MainActivity`, `VpnRequestActivity`, `SwitchActivity`, `ScannerActivity`, `AppManagerActivity`, `GroupSettingsActivity`, `RouteSettingsActivity`, `AssetsActivity`, `QuickToggleShortcut`, and 26 `*SettingsActivity` for profiles (e.g. `SocksSettingsActivity`, `VMessSettingsActivity`, etc).
- **Services (5)**: `ProxyService`, `VpnService`, `TileService`, `AppLocalesMetadataHolderService`, `MultiInstanceInvalidationService`.
- **Receivers (2)**: `BootReceiver`, `TaskerReceiver`.
- **Providers (2)**: `FileProvider`, `InitializationProvider`.

**Watch Variant Adjustments (`app/src/watch/AndroidManifest.xml`):**
- **Keep**: `ProxyService`, `VpnService`, `TileService`, `BootReceiver`, `VpnRequestActivity` (used for VPN dialogs).
- **Remove**: `CAMERA` permission, `ScannerActivity`, and all 26+ Profile Settings Activities.
- **Conflicts**: `io.nekohasekai.sagernet.ui.MainActivity` defines the `<action android:name="android.intent.action.MAIN" />` and `<category android:name="android.intent.category.LAUNCHER" />`. If the watch flavor defines a new launcher Activity, it will result in two app icons. The watch manifest overlay must explicitly remove the intent filters from `MainActivity` using `tools:node="remove"`.

## 3. Reverse Dependencies (Monolithic Coupling)

Non-UI packages are heavily coupled to UI classes in `:app`, which will break if phone UI Activities are excluded from the watch variant:
- `io.nekohasekai.sagernet.database.ProxyEntity`: The `settingIntent(ctx: Context, isSubscription: Boolean)` method statically references and returns Intents for 26 UI classes like `SocksSettingsActivity::class.java`, `SnellSettingsActivity::class.java`, etc.
- `io.nekohasekai.sagernet.bg.VpnService`: Imports and launches `io.nekohasekai.sagernet.ui.VpnRequestActivity` via Intent in `onStartCommand`.
- `io.nekohasekai.sagernet.bg.ServiceNotification`: Imports and references `io.nekohasekai.sagernet.ui.SwitchActivity` in its PendingIntent.
- `io.nekohasekai.sagernet.group.GroupInterfaceAdapter`: Constructor requires an `io.nekohasekai.sagernet.ui.ThemedActivity`.
- `io.nekohasekai.sagernet.SagerNet`: References `io.nekohasekai.sagernet.ui.MainActivity::class.java` to build the PendingIntent for notifications.

## 4. Minimal Control API

Application state and control flow through `SagerConnection` and `SagerNet`.

**Minimal pseudo-code for a new Watch Activity to control the proxy:**

```kotlin
// 1. Initialize Connection
val connection = SagerConnection(true)

// 2. Connect and listen for state
connection.connect(context, object : SagerConnection.Callback {
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        // Update Watch UI (e.g. Connected/Idle)
    }
    override fun onServiceConnected(service: ISagerNetService) { /* Bound to Binder */ }
    override fun trafficUpdated(profileId: Long, stats: TrafficStats, isCurrent: Boolean) { /* Stats */ }
})

// 3. Start Service (This routes through SagerNet.startService() -> ContextCompat.startForegroundService)
// VpnService will internally launch VpnRequestActivity if it needs to request VPN permission via VpnService.prepare(this)
SagerNet.startService()

// 4. Stop Service
SagerNet.stopService()
```

## 5. Profiles/Subscriptions without UI

A minimal non-UI sequence to interact with data.

**a) Create Subscription Group:**
```kotlin
val group = ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
    subscription = SubscriptionBean().apply { link = url; name = "Subscription" }
}
GroupManager.createGroup(group) // Persists via SagerDatabase.groupDao
```

**b) Update Subscription:**
```kotlin
// Note: This requires an Activity/Context because it alerts the UI with diffs via GroupManager.userInterface
GroupUpdater.startUpdate(group, byUser = true)
```

**c) List Profiles:**
```kotlin
val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
```

**d) Select Profile:**
```kotlin
DataStore.selectedProxy = profileId
SagerNet.reloadService() // Reloads active service with new profile
```

## 6. Resources

Non-UI background code depends on app themes, icons, and strings that a watch variant must provide:
- `io.nekohasekai.sagernet.bg.TileService`: Relies on `R.drawable.ic_service_active`.
- `io.nekohasekai.sagernet.bg.ServiceNotification`: Calls `Theme.apply(app)` and uses `R.string.app_name`.
- `io.nekohasekai.sagernet.group.GroupInterfaceAdapter`: Uses `R.string.confirm`, `R.string.group_diff`, `R.plurals.group_updated`, etc.
- `io.nekohasekai.sagernet.SagerNet`: `configureIntent` uses `MainActivity::class.java`.

## 7. Settings Inventory

Here is the exhaustive inventory of all Activities/Fragments that edit profiles or settings, the preference XML they use, the number of preferences in each, and an example of code logic (visibility/validation) per screen:

- **GroupSettingsActivity.kt** (`res/xml/group_preferences.xml`): 12 preferences. Logic: `groupSubscription.isVisible = isSubscription`
- **RouteSettingsActivity.kt** (`res/xml/route_preferences.xml`): 11 preferences. Logic: `ssid.isVisible = newValues.contains("wifi")`
- **SettingsPreferenceFragment.kt** (`res/xml/global_preferences.xml`): 59 preferences. Logic: `appendHttpProxy.isVisible = requireHttp.isChecked && newValue == MODE_VPN`
- **AnyTLSSettingsActivity.kt** (`res/xml/anytls_preferences.xml`): 27 preferences. Logic: `securityCategory.isVisible = security == "tls" || security == "reality"`
- **BalancerSettingsActivity.kt** (`res/xml/balancer_preferences.xml`): 7 preferences. Logic: Validation logic is handled via preference change listeners.
- **ChainSettingsActivity.kt** (`res/xml/name_preferences.xml`): 2 preferences. Logic: Code defines custom logic via `setOnPreferenceChangeListener`.
- **ConfigSettingsActivity.kt** (`res/xml/config_preferences.xml`): 3 preferences. Logic: `serverAddresses.isVisible = protocol == "v2ray_outbound"`
- **Http3SettingsActivity.kt** (`res/xml/http3_preferences.xml`): 23 preferences. Logic: Validation handled via text watchers.
- **Hysteria2SettingsActivity.kt** (`res/xml/hysteria2_preferences.xml`): 28 preferences. Logic: `obfsPassword.isVisible = obfsType.value.isNotEmpty()`
- **JuicitySettingsActivity.kt** (`res/xml/juicity_preferences.xml`): 19 preferences. Logic: Input format validation handled programmatically.
- **MASQUESettingsActivity.kt** (`res/xml/masque_preferences.xml`): 23 preferences. Logic: `securityCategory.isVisible = isTLS`
- **MieruSettingsActivity.kt** (`res/xml/mieru_preferences.xml`): 9 preferences. Logic: `mtu.isVisible = protocol.value == "${MieruBean.PROTOCOL_UDP}"`
- **NaiveSettingsActivity.kt** (`res/xml/naive_preferences.xml`): 16 preferences. Logic: `findPreference<PreferenceCategory>(Key.SERVER_SING_UOT_CATEGORY)!!.isVisible = ...`
- **SSHSettingsActivity.kt** (`res/xml/ssh_preferences.xml`): 11 preferences. Logic: `password.isVisible = type == SSHBean.AUTH_TYPE_PASSWORD`
- **ShadowQUICSettingsActivity.kt** (`res/xml/shadowquic_preferences.xml`): 11 preferences. Logic: Field bindings manually synced in code.
- **ShadowsocksRSettingsActivity.kt** (`res/xml/shadowsocksr_preferences.xml`): 8 preferences. Logic: Relies on base class logic.
- **SnellSettingsActivity.kt** (`res/xml/snell_preferences.xml`): 11 preferences. Logic: `findPreference<PreferenceCategory>(Key.SERVER_SING_SNELL_CATEGORY)!!.isVisible = ...`
- **StandardV2RaySettingsActivity.kt** (`res/xml/standard_v2ray_preferences.xml`): 65 preferences. Logic: `httpConnectUDPCategory.isVisible = bean is HttpBean`
- **TrustTunnelSettingsActivity.kt** (`res/xml/trusttunnel_preferences.xml`): 19 preferences. Logic: `utlsFingerprint.isVisible = protocol.value == "https"`
- **Tuic5SettingsActivity.kt** (`res/xml/tuic5_preferences.xml`): 24 preferences. Logic: `findPreference<PreferenceCategory>(Key.SERVER_SING_UOT_CATEGORY)!!.isVisible = ...`
- **WireGuardSettingsActivity.kt** (`res/xml/wireguard_preferences.xml`): 12 preferences. Logic: Relies on base class UI binding.

*(Note: The remaining settings activities like `HttpSettingsActivity`, `SocksSettingsActivity`, `ShadowsocksSettingsActivity`, `VMessSettingsActivity`, `VLESSSettingsActivity`, `TrojanSettingsActivity` inherit directly from `StandardV2RaySettingsActivity` and reuse its XML and logic without defining their own.)*

**Totals:**
- Total Screens: 42 (Fragments and Activities)
- Total Preferences across XMLs: ~400
- Distinct Widget Types: 3 (e.g., Preference, SwitchPreference, EditTextPreference)

## 8. Risks (Flavor Approach)

| Risk | Rating | Details |
|---|---|---|
| **Compilation Blockers in `src/main`** | **High** | The core data model (`ProxyEntity.kt`) hard-links to 26 UI Activities. If these UI Activities are excluded from the watch flavor to save space and remove AppCompat, `ProxyEntity.kt` will fail to compile. Fixing this in a single module without extraction requires creating 26 dummy stub Activities in `app/src/watch/java`, which is a maintenance nightmare. |
| **AppCompat / Material Coupling** | **High** | Core managers (`GroupInterfaceAdapter.kt`) require `io.nekohasekai.sagernet.ui.ThemedActivity` to display `MaterialAlertDialogBuilder` popups upon subscription updates. This logic cannot run headlessly or seamlessly on Wear OS without crashing or refactoring. |
| **Gradle Complexity & Leakage** | **Medium** | Enabling `buildFeatures.compose = true` in `:app` and configuring Wear OS dependencies requires careful configuration to ensure Compose and Wear dependencies do not leak into the `oss` and `legacy` phone builds, which use traditional XML Views. |
