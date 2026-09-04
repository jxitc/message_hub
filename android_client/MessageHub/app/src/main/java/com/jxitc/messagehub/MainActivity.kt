package com.jxitc.messagehub

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jxitc.messagehub.presentation.screen.AddMemoryScreen
import com.jxitc.messagehub.presentation.screen.MemoryListScreen
import com.jxitc.messagehub.presentation.screen.SettingsScreen
import com.jxitc.messagehub.ui.theme.MessageHubTheme
import com.jxitc.messagehub.utils.PermissionHelper
import com.jxitc.messagehub.utils.Logger

class MainActivity : ComponentActivity() {
    
    private val appContainer by lazy {
        (application as MessageHubApplication).appContainer
    }
    
    // Permission launcher for SMS and contacts permissions
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Logger.d("MainActivity", "Permission results: $permissions")
        permissions.entries.forEach { entry ->
            val permission = entry.key
            val isGranted = entry.value
            Logger.d("MainActivity", "$permission granted: $isGranted")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Check and request permissions on startup
        checkAndRequestPermissions()
        
        setContent {
            MessageHubTheme {
                MessageHubApp(appContainer, ::requestDataCollectionPermissions)
            }
        }
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = PermissionHelper.getMissingPermissions(this)
        if (missingPermissions.isNotEmpty()) {
            Logger.i("MainActivity", "Requesting missing permissions: $missingPermissions")
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Logger.i("MainActivity", "All permissions already granted")
        }
    }
    
    private fun requestDataCollectionPermissions() {
        val allPermissions = PermissionHelper.SMS_PERMISSIONS +
            PermissionHelper.CONTACTS_PERMISSIONS +
            PermissionHelper.NOTIFICATION_PERMISSIONS
        requestPermissionsLauncher.launch(allPermissions)
    }
}

@Composable
fun MessageHubApp(
    appContainer: com.jxitc.messagehub.di.AppContainer,
    requestPermissions: () -> Unit = {}
) {
    val navController = rememberNavController()
    
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "memory_list",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("memory_list") {
                val viewModel = remember { appContainer.createMemoryListViewModel() }
                val context = LocalContext.current
                // 长按屏蔽/取消屏蔽 app：写入黑名单(SharedPreferences) + Toast; 只拦新通知, 旧记录保留
                var blockedApps by remember { mutableStateOf(appContainer.appPreferences.blockedApps) }
                MemoryListScreen(
                    viewModel = viewModel,
                    blockedApps = blockedApps,
                    onToggleBlock = { pkg ->
                        val prefs = appContainer.appPreferences
                        val nowBlocked = prefs.isAppBlocked(pkg)
                        if (nowBlocked) prefs.removeBlockedApp(pkg) else prefs.addBlockedApp(pkg)
                        blockedApps = prefs.blockedApps
                        Toast.makeText(
                            context,
                            if (nowBlocked) "已取消屏蔽 $pkg 的通知" else "已屏蔽 $pkg 的通知，后续新通知不再记录",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onNavigateToAddMemory = {
                        navController.navigate("add_memory")
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    }
                )
            }
            
            composable("add_memory") {
                val viewModel = remember { appContainer.createAddMemoryViewModel() }
                AddMemoryScreen(
                    viewModel = viewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            
            composable("settings") {
                val viewModel = remember { appContainer.createSettingsViewModel() }
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    requestPermissions = requestPermissions
                )
            }
        }
    }
}

