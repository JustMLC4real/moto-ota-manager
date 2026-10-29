package nl.moto.ota

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.moto.ota.core.update.UpdateStage
import nl.moto.ota.platform.OfficialApps
import nl.moto.ota.platform.download.DownloadNotifications
import nl.moto.ota.platform.monitoring.MonitorNotifications
import nl.moto.ota.ui.*

class MainActivity : ComponentActivity() {
    private var firmwareRequest by mutableIntStateOf(0)
    private var updateRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getStringExtra("moto_open_tab") == "firmware") firmwareRequest++
        if (intent.getStringExtra("moto_open_tab") == "update") updateRequest++
        setContent { MotoApp(openFirmwareRequest = firmwareRequest,openUpdateRequest=updateRequest) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("moto_open_tab") == "firmware") firmwareRequest++
        if (intent.getStringExtra("moto_open_tab") == "update") updateRequest++
    }
}

enum class Destination(val title: Int, val icon: Int, val tag: String) {
    OVERVIEW(R.string.ui_nav_overview, R.drawable.ic_nav_overview, "nav_overview"),
    FIRMWARE(R.string.ui_nav_firmware, R.drawable.ic_nav_firmware, "nav_firmware"),
    UPDATE(R.string.ui_nav_workflow, R.drawable.ic_nav_update, "nav_update"),
    DIAGNOSTICS(R.string.ui_nav_diagnostics, R.drawable.ic_nav_diagnostics, "nav_diagnostics"),
    SETTINGS(R.string.ui_nav_settings, R.drawable.ic_nav_settings, "nav_settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotoApp(vm: MotoViewModel = viewModel(), openFirmwareRequest: Int = 0,openUpdateRequest:Int=0) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    var tab by rememberSaveable { mutableStateOf(Destination.OVERVIEW) }
    var reviewTour by rememberSaveable { mutableStateOf(false) }
    var rootDialog by remember { mutableStateOf(false) }
    var onlineDialog by remember { mutableStateOf(false) }
    var launchFailure by remember { mutableStateOf<UiMessage?>(null) }
    var notificationAction by rememberSaveable { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.onResume() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(configuration) {
        MonitorNotifications.refreshChannel(context)
        DownloadNotifications.refreshChannel(context)
    }
    LaunchedEffect(openFirmwareRequest) { if (openFirmwareRequest > 0) tab = Destination.FIRMWARE }
    LaunchedEffect(openUpdateRequest) {if(openUpdateRequest>0)tab=Destination.UPDATE}
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (notificationAction == "monitor") vm.setMonitoring(true)
        if (notificationAction == "download") vm.download()
        notificationAction = ""
    }
    fun withNotifications(action: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            if (action == "monitor") vm.setMonitoring(true) else vm.download()
        } else { notificationAction = action; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    val otaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importOta) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importBackup) }
    val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::export) }
    val recoveryPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(vm::exportRecoveryBackup) }
    fun openOfficial(magisk: Boolean) {
        val intent = if (magisk) OfficialApps.magiskIntent(context.packageManager) else OfficialApps.systemUpdaterIntent(context.packageManager)
        if (intent == null) { launchFailure = uiMessage(if (magisk) R.string.ui_magisk_not_found else R.string.ui_updater_not_found); return }
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { launchFailure = uiMessage(R.string.ui_updater_not_found) }
        catch (_: SecurityException) { launchFailure = uiMessage(R.string.ui_official_app_denied) }
    }
    val actions = ScreenActions(
        checkDevice = { rootDialog = true },
        checkOnline = { if (vm.online.consentGranted) vm.checkOnlineUpdates() else onlineDialog = true },
        download = { withNotifications("download") },
        monitor = { enabled -> if (enabled) withNotifications("monitor") else vm.setMonitoring(false) },
        openUpdater = { openOfficial(false) }, openMagisk = { openOfficial(true) },
        importOta = { otaPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
        importImage = { backupPicker.launch(arrayOf("*/*")) },
        exportReport = { reportPicker.launch("moto-ota-diagnostics.txt") },
        exportRecovery = { recoveryPicker.launch("moto-patched-boot-backup.img") },
        navigate = { tab = it },
        startUpdate = { vm.startOneTapUpdate() }
    )
    MotoTheme(settings.appearance) {
        val dark = when (settings.appearance) { AppAppearance.SYSTEM -> isSystemInDarkTheme(); AppAppearance.DARK -> true; AppAppearance.LIGHT -> false }
        SideEffect {
            (context as? ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            )
        }
        if (reviewTour || (!settings.tourCompleted && !vm.workflow.criticalSession)) {
            SetupTour(settings) { reviewTour = false }
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text(stringResource(R.string.ui_app_name), style = MaterialTheme.typography.titleLarge) }, actions = {
                        IconButton(onClick = { reviewTour = true }, modifier = Modifier.testTag("open_setup")) {
                            Icon(painterResource(R.drawable.ic_nav_help), stringResource(R.string.ui_help))
                        }
                    })
                },
                bottomBar = {
                    if (!wide) NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(selected = tab == destination, onClick = { tab = destination },
                                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                                label = { Text(stringResource(destination.title), maxLines = 1) },
                                modifier = Modifier.testTag(destination.tag))
                        }
                    }
                }
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide) NavigationRail {
                        Spacer(Modifier.height(12.dp))
                        Destination.entries.forEach { destination ->
                            NavigationRailItem(selected = tab == destination, onClick = { tab = destination },
                                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                                label = { Text(stringResource(destination.title)) }, modifier = Modifier.testTag(destination.tag))
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        key(tab) {
                            LazyColumn(state = rememberLazyListState(), modifier = Modifier.widthIn(max = 960.dp).fillMaxHeight().testTag("screen_${tab.tag}"),
                                contentPadding = PaddingValues(horizontal = if (wide) 32.dp else 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                        if (vm.demo) Notice(stringResource(R.string.ui_simulation), vm.message.resolve(context))
                                        if (!vm.demo && vm.workflow.criticalSession) {
                                            val sourceBoot = vm.device.bootId.isBlank() || vm.device.bootId == vm.workflow.sourceBootId
                                            Notice(stringResource(if (sourceBoot) R.string.ui_critical_no_reboot else R.string.ui_critical_final),
                                                stringResource(if (sourceBoot) R.string.ui_critical_no_reboot_body else R.string.ui_critical_final_body), critical = true)
                                        }
                                        when (tab) {
                                            Destination.OVERVIEW -> OverviewScreen(vm, actions)
                                            Destination.FIRMWARE -> FirmwareScreen(vm, actions)
                                            Destination.UPDATE -> UpdateScreen(vm, actions)
                                            Destination.DIAGNOSTICS -> DiagnosticsScreen(vm, actions)
                                            Destination.SETTINGS -> SettingsScreen(settings,onlineConsent=vm.online.consentGranted,
                                                canChangeNetwork=!vm.busy,onOnlineConsent=vm::setOnlineConsent) { reviewTour = true }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (rootDialog) AlertDialog(onDismissRequest = { rootDialog = false },
            title = { Text(stringResource(R.string.ui_root_diagnostic_title)) }, text = { Text(stringResource(R.string.ui_root_diagnostic_body)) },
            confirmButton = { TextButton(onClick = { rootDialog = false; vm.scan(true) }) { Text(stringResource(R.string.ui_read_diagnostics)) } },
            dismissButton = { TextButton(onClick = { rootDialog = false }) { Text(stringResource(R.string.ui_cancel)) } })
        if (onlineDialog) AlertDialog(onDismissRequest = { onlineDialog = false },
            title = { Text(stringResource(R.string.ui_online_privacy)) }, text = { Text(stringResource(R.string.ui_online_privacy_body)) },
            confirmButton = { TextButton(onClick = { onlineDialog = false; vm.setOnlineConsent(true) }) { Text(stringResource(R.string.ui_online_enable)) } },
            dismissButton = { TextButton(onClick = { onlineDialog = false }) { Text(stringResource(R.string.ui_cancel)) } })
        launchFailure?.let { failure -> AlertDialog(onDismissRequest = { launchFailure = null }, title = { Text(stringResource(R.string.ui_official_app_title)) },
            text = { Text(failure.resolve(context)) }, confirmButton = { TextButton(onClick = { launchFailure = null }) { Text(stringResource(R.string.ui_close)) } }) }
    }
}
