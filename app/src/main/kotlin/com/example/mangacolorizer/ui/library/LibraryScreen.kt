package com.example.mangacolorizer.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mangacolorizer.ui.reader.ReaderScreen
import com.example.mangacolorizer.ui.reader.ReaderViewModel

@Composable
fun LibraryScreen(viewModel: ReaderViewModel) {
    val pages by viewModel.pages.collectAsState()
    val hasSelectedImages = pages.isNotEmpty()

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.loadPages(uris.map { it.toString() })
        }
    }

    if (!hasSelectedImages) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Your Manga Library", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { pickerLauncher.launch("image/*") }) {
                Text("Open Local Manga Files")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Select images from your device to colorize manually", 
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = { viewModel.clearPages() }) {
                    Text("Close Reader")
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ReaderScreen(viewModel)
            }
        }
    }
}
