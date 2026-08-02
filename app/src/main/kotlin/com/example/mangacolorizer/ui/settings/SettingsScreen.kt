package com.example.mangacolorizer.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mangacolorizer.inference.MangaColorizer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(colorizer: MangaColorizer) {
    val context = LocalContext.current
    var liveColoring by remember { mutableStateOf(true) }
    var denoise by remember { mutableStateOf(false) }
    var upscale by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (colorizer.isNpuSupported) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Device Hardware Support", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (colorizer.isNpuSupported) "Snapdragon NPU Acceleration: ACTIVE" else "Snapdragon NPU Acceleration: NOT SUPPORTED",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsToggle(
            title = "Live Browser Colorization",
            subtitle = "Automatically colorize images while browsing",
            checked = liveColoring,
            onCheckedChange = { liveColoring = it }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("Debugging", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Log Save Permission")
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { /* Cache clearing logic */ },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear Colorization Cache")
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text("Manga Colorizer Live v1.2 - Optimized for Poco F6", 
             style = MaterialTheme.typography.labelSmall,
             modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
