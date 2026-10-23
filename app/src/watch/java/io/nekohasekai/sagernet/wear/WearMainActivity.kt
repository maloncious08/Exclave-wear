package io.nekohasekai.sagernet.wear

import android.content.Intent
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.aidl.ISagerNetService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.Logs
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.RadioButton
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Button
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.ProxyEntity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.compose.ui.text.style.TextAlign
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

class WearMainActivity : ComponentActivity() {
    private val connection = SagerConnection(true)
    private val _serviceState = MutableStateFlow(BaseService.State.Idle)
    val serviceState: StateFlow<BaseService.State> = _serviceState.asStateFlow()
    private val _currentProfileName = MutableStateFlow<String?>("")
    val currentProfileName: StateFlow<String?> = _currentProfileName.asStateFlow()

    private val sagerCallback = object : SagerConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
            _serviceState.value = state
            _currentProfileName.value = profileName ?: ""
        }
        override fun onServiceConnected(service: ISagerNetService) {}
    }

    private val watchGroupInterface = object : GroupManager.Interface {
        override suspend fun confirm(message: String): Boolean = true
        override suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>
        ) {
            Logs.i("Group update success: ${group.displayName()}")
        }
        override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
            Logs.e("Group update failure: ${group.displayName()}, $message")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GroupManager.userInterface = watchGroupInterface

        handleIntent(intent)

        setContent {
            WearApp(this)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("subscription_url")
        if (!url.isNullOrBlank()) {
            val group = ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                name = "Imported Subscription"
                subscription = SubscriptionBean().apply { link = url }
            }
            io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher {
                GroupManager.createGroup(group)
                GroupUpdater.startUpdate(group, byUser = true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect(this, sagerCallback)
    }

    override fun onStop() {
        super.onStop()
        connection.disconnect(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (GroupManager.userInterface == watchGroupInterface) {
            GroupManager.userInterface = null
        }
    }
}

@Composable
fun WearApp(activity: WearMainActivity) {
    MaterialTheme {
        val navController = rememberSwipeDismissableNavController()

        Scaffold(
            timeText = { TimeText() }
        ) {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
            ) {
                composable("home") {
                    Home(activity, navController)
                }
                composable("profiles") {
                    Profiles(activity)
                }
                composable("subscriptions") {
                    Subscriptions(activity)
                }
                composable("more") {
                    More(activity)
                }
            }
        }
    }
}

@Composable
fun Home(activity: WearMainActivity, navController: androidx.navigation.NavController) {
    val state by activity.serviceState.collectAsState()
    val profileName by activity.currentProfileName.collectAsState()
    val isRunning = state == BaseService.State.Connected || state == BaseService.State.Connecting

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = if (isRunning) profileName ?: "Connected" else "Stopped")
        Spacer(modifier = Modifier.height(8.dp))
        ToggleChip(
            checked = isRunning,
            onCheckedChange = { checked ->
                if (checked) {
                    SagerNet.startService()
                } else {
                    SagerNet.stopService()
                }
            },
            label = { Text("Proxy") },
            toggleControl = {
                Switch(
                    checked = isRunning,
                    onCheckedChange = null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { navController.navigate("profiles") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Profiles")
        }
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { navController.navigate("subscriptions") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Subscriptions")
        }
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { navController.navigate("more") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("More")
        }
    }
}

@Composable
fun Profiles(activity: WearMainActivity) {
    var profiles by remember { mutableStateOf<List<ProxyEntity>>(emptyList()) }
    var selectedId by remember { mutableStateOf(DataStore.selectedProxy) }

    LaunchedEffect(Unit) {
        runOnDefaultDispatcher {
            profiles = SagerDatabase.proxyDao.getAll()
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Text("Profiles")
            }
        }
        items(profiles) { profile ->
            ToggleChip(
                checked = selectedId == profile.id,
                onCheckedChange = {
                    DataStore.selectedProxy = profile.id
                    selectedId = profile.id
                    SagerNet.reloadService()
                },
                label = { Text(profile.displayName()) },
                toggleControl = {
                    RadioButton(
                        selected = selectedId == profile.id,
                        onClick = null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                val intent = profile.settingIntent(activity, false)
                                if (intent != null) {
                                    activity.startActivity(intent)
                                }
                            },
                            onTap = {
                                DataStore.selectedProxy = profile.id
                                selectedId = profile.id
                                SagerNet.reloadService()
                            }
                        )
                    }
            )
        }
    }
}

@Composable
fun Subscriptions(activity: WearMainActivity) {
    var groups by remember { mutableStateOf<List<ProxyGroup>>(emptyList()) }

    LaunchedEffect(Unit) {
        runOnDefaultDispatcher {
            groups = SagerDatabase.groupDao.allGroups().filter { it.type == GroupType.SUBSCRIPTION }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Text("Subscriptions")
            }
        }
        items(groups) { group ->
            Chip(
                onClick = {
                    GroupUpdater.startUpdate(group, byUser = true)
                },
                label = { Text(group.displayName()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
fun More(activity: WearMainActivity) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Phone UI Settings")
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val intent = Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Advanced Settings")
        }
    }
}
