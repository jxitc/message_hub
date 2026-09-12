package com.jxitc.messagehub.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jxitc.messagehub.presentation.viewmodel.SettingsViewModel
import com.jxitc.messagehub.utils.PermissionHelper
import com.jxitc.messagehub.utils.NotificationPermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit = {},
    requestPermissions: () -> Unit = {}
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val autoSync by viewModel.autoSync.collectAsStateWithLifecycle()
    val syncOnlyOnWifi by viewModel.syncOnlyOnWifi.collectAsStateWithLifecycle()
    val blockedApps by viewModel.blockedApps.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val healthCheckResult by viewModel.healthCheckResult.collectAsStateWithLifecycle()
    val currentVersion by viewModel.currentVersion.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    val updateBusy by viewModel.updateBusy.collectAsStateWithLifecycle()
    val deviceId by viewModel.deviceId.collectAsStateWithLifecycle()
    // 输入框直接预填当前生效的标识；deviceId 变化时同步刷新
    var deviceNameInput by remember(deviceId) { mutableStateOf(deviceId) }
    
    val context = LocalContext.current

    // Check permissions - re-check periodically while screen is visible
    var hasSmsPermissions by remember { mutableStateOf(PermissionHelper.hasSmsPermissions(context)) }
    var hasContactsPermission by remember { mutableStateOf(PermissionHelper.hasContactsPermission(context)) }
    var hasNotificationAccess by remember { mutableStateOf(NotificationPermissionHelper.isNotificationAccessEnabled(context)) }
    var isNotificationServiceActive by remember { mutableStateOf(NotificationPermissionHelper.isNotificationServiceActive(context)) }

    // Periodically re-check permissions and service status while screen is visible
    LaunchedEffect(Unit) {
        while (true) {
            hasSmsPermissions = PermissionHelper.hasSmsPermissions(context)
            hasContactsPermission = PermissionHelper.hasContactsPermission(context)
            hasNotificationAccess = NotificationPermissionHelper.isNotificationAccessEnabled(context)
            isNotificationServiceActive = NotificationPermissionHelper.isNotificationServiceActive(context)

            // Re-check every 2 seconds
            kotlinx.coroutines.delay(2000)
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top App Bar
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Server Configuration Section
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Server Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = viewModel::updateServerUrl,
                        label = { Text("Server URL") },
                        placeholder = { Text("http://localhost:8000") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        supportingText = {
                            Text("MessageHub server address (include http://)")
                        }
                    )
                    
                    // Health Check Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = viewModel::testConnection,
                            enabled = !isLoading && serverUrl.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing...")
                            } else {
                                Text("Test Connection")
                            }
                        }
                    }
                    
                    // Health Check Result
                    healthCheckResult?.let { result ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (result.startsWith("✅")) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                }
                            )
                        ) {
                            Text(
                                text = result,
                                modifier = Modifier.padding(12.dp),
                                color = if (result.startsWith("✅")) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            )
                        }
                    }
                }
            }
            
            // Data Collection Permissions Section
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Data Collection Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    // SMS Permission Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SMS Monitoring",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Required to capture and process SMS messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (hasSmsPermissions) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Granted",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Granted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Not Granted",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Not Granted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    
                    // Contacts Permission Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Contacts Access",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Used to resolve contact names for SMS messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (hasContactsPermission) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Granted",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Granted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Not Granted",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Not Granted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Notification Access Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification Access",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Required to capture and process notifications",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when {
                                !hasNotificationAccess -> {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Disabled",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Disabled",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                !isNotificationServiceActive -> {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Service Disconnected",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Disconnected",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Active",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Active",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Service Disconnected Warning (Permission granted but service not active)
                    if (hasNotificationAccess && !isNotificationServiceActive) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warning",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "Service Disconnected",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }

                                Text(
                                    text = "Notification permission is granted, but the service is not running. This can happen on OPPO/ColorOS devices due to aggressive battery management.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )

                                Button(
                                    onClick = {
                                        NotificationPermissionHelper.openNotificationListenerSettings(context)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Fix: Toggle Notification Access OFF then ON")
                                }

                                Text(
                                    text = "Steps: Open Settings → Find MessageHub → Toggle OFF → Toggle ON",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Open Notification Settings Button
                    if (!hasNotificationAccess) {
                        Button(
                            onClick = { NotificationPermissionHelper.openNotificationListenerSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enable Notification Access")
                        }

                        Text(
                            text = "Tap to open Settings → find MessageHub → toggle ON",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Request Permissions Button (SMS/Contacts)
                    if (!hasSmsPermissions || !hasContactsPermission) {
                        Button(
                            onClick = requestPermissions,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Permissions")
                        }
                    }
                }
            }
            
            // Sync Settings Section
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Sync Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto Sync",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Automatically sync memories to server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoSync,
                            onCheckedChange = viewModel::updateAutoSync
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "WiFi Only",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Only sync when connected to WiFi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = syncOnlyOnWifi,
                            onCheckedChange = viewModel::updateSyncOnlyOnWifi,
                            enabled = autoSync
                        )
                    }
                }
            }

            // Ignored/blocked notification apps section
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "已忽略的通知 apps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (blockedApps.isEmpty()) {
                        Text(
                            text = "没有忽略任何 app。在主页长按某个通知条目可屏蔽该 app 的通知。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        blockedApps.sorted().forEach { pkg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = pkg,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(onClick = { viewModel.removeBlockedApp(pkg) }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "取消屏蔽 $pkg",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Error Display
            error?.let { errorMessage ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            // 本机设备标识：多台设备时，服务器靠它区分消息来源
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "本机设备",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "多台手机同时上报时，服务器靠这个标识区分来源（就是消息的 source_device_id）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = deviceNameInput,
                        onValueChange = { deviceNameInput = it },
                        label = { Text("设备标识") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.setDeviceId(deviceNameInput) },
                            enabled = deviceNameInput.trim().isNotEmpty() &&
                                      deviceNameInput.trim() != deviceId
                        ) {
                            Text("保存")
                        }
                        OutlinedButton(onClick = { viewModel.resetDeviceId() }) {
                            Text("恢复默认")
                        }
                    }
                }
            }

            // App update (built-in OTA: checks /api/v1/releases/latest-info)
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "App 更新",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "当前版本：$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    availableUpdate?.let { info ->
                        Text(
                            text = "新版本 ${info.versionName}（${info.sizeMb} MB）可用",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (info.notes.isNotBlank()) {
                            Text(
                                text = info.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    updateStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.checkForUpdate() },
                            enabled = !updateBusy
                        ) {
                            Text(if (updateBusy) "处理中…" else "检查更新")
                        }
                        if (availableUpdate != null) {
                            Button(
                                onClick = { viewModel.downloadAndInstallUpdate() },
                                enabled = !updateBusy
                            ) {
                                Text("下载并安装")
                            }
                        }
                    }

                    Text(
                        text = "注：下载后系统会弹出安装确认（Android 不允许应用静默自升级），点\"安装\"即可覆盖，数据保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Info Section
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    Text(
                        text = "Default server URL for Android emulator: http://10.0.2.2:8000",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        text = "For physical devices, use your computer's IP address (e.g., http://192.168.1.100:8000)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}