package com.example.mangacolorizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mangacolorizer.inference.MangaColorizer
import com.example.mangacolorizer.ui.browse.BrowseScreen
import com.example.mangacolorizer.ui.library.LibraryScreen
import com.example.mangacolorizer.ui.settings.SettingsScreen
import com.example.mangacolorizer.ui.theme.MangaColorizerTheme
import com.example.mangacolorizer.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var colorizer: MangaColorizer

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> Logger.i("MainActivity: Screen OFF detected")
                Intent.ACTION_SCREEN_ON -> Logger.i("MainActivity: Screen ON detected")
                Intent.ACTION_USER_PRESENT -> Logger.i("MainActivity: Device unlocked (User Present)")
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (isGranted) {
            Logger.i("MainActivity: Notification permission granted")
        } else {
            Logger.w("MainActivity: Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("MainActivity: onCreate (Starting Application)")
        enableEdgeToEdge()

        checkPermissions()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        setContent {
            MangaColorizerTheme {
                MainContainer(colorizer)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Logger.i("MainActivity: onStart")
    }

    override fun onResume() {
        super.onResume()
        Logger.i("MainActivity: onResume (App in Foreground)")
    }

    override fun onPause() {
        super.onPause()
        Logger.i("MainActivity: onPause (App losing focus)")
    }

    override fun onStop() {
        super.onStop()
        Logger.i("MainActivity: onStop (App in Background)")
    }

    override fun onDestroy() {
        Logger.i("MainActivity: onDestroy (Process finishing)")
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // Safe to ignore
        }
        super.onDestroy()
    }
    
    private fun checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Logger.i("MainActivity: Requesting Manage External Storage permission")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }
}

@Composable
fun MainContainer(colorizer: MangaColorizer) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination?.route

    var isModelLoaded by remember { mutableStateOf(value = false) }
    var errorMessage by remember { mutableStateOf<String?>(value = null) }

    LaunchedEffect(Unit) {
        try {
            Logger.i("MainContainer: Loading alacgan_qdq.onnx model...")
            colorizer.loadModel("alacgan_qdq.onnx")
            isModelLoaded = true
            Logger.i("MainContainer: Model loaded successfully")
        } catch (e: Exception) {
            errorMessage = e.message ?: "Unknown AI Model Error"
            Logger.e("MainContainer: Critical failure loading AI model", e)
        }
    }

    if (!isModelLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (errorMessage != null) {
                Text("AI Engine Failure: $errorMessage", color = MaterialTheme.colorScheme.error)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Optimizing AI Engine for Poco F6...")
                }
            }
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentDestination == "library",
                    onClick = { 
                        Logger.i("Navigation: Tab change -> Library")
                        navController.navigate("library") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Library") }
                )
                NavigationBarItem(
                    selected = currentDestination == "browse",
                    onClick = { 
                        Logger.i("Navigation: Tab change -> Browse")
                        navController.navigate("browse") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Browse") }
                )
                NavigationBarItem(
                    selected = currentDestination == "settings",
                    onClick = { 
                        Logger.i("Navigation: Tab change -> Settings")
                        navController.navigate("settings") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "library",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("library") {
                LibraryScreen(viewModel = hiltViewModel())
            }
            composable("browse") {
                BrowseScreen(
                    viewModel = hiltViewModel(),
                ) {
                    Logger.i("Navigation: Browse screen closed by user")
                    navController.navigate("library") {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
            }
            composable("settings") {
                SettingsScreen(colorizer)
            }
        }
    }
}
