package com.example.mangacolorizer.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModel
import com.example.mangacolorizer.inference.ColorizationManager
import com.example.mangacolorizer.service.ColorizationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val colorizationManager: ColorizationManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _currentUrl = MutableStateFlow("https://www.google.com")
    val currentUrl = _currentUrl.asStateFlow()

    val isColorizing = colorizationManager.isColorizing
    val isPaused = colorizationManager.isPaused
    val processingCount = colorizationManager.queueSize

    var webViewBundle: android.os.Bundle? = null

    init {
        // Sync with existing background state if already running
        if (!colorizationManager.isPaused.value && colorizationManager.queueSize.value > 0) {
            updateServiceState()
        }
    }

    fun updateUrl(url: String) {
        if (_currentUrl.value != url) {
            _currentUrl.value = url
        }
    }

    fun togglePause() {
        colorizationManager.togglePause()
        updateServiceState()
    }

    fun processDetectedImage(id: String, src: String, referer: String, onComplete: (String) -> Unit) {
        colorizationManager.addImage(id, src, referer, onComplete)
        if (!colorizationManager.isPaused.value) {
            updateServiceState()
        }
    }

    private fun updateServiceState() {
        if (colorizationManager.isPaused.value || colorizationManager.queueSize.value == 0) {
            val intent = Intent(context, ColorizationService::class.java).apply {
                action = ColorizationService.ACTION_STOP
            }
            context.startService(intent)
        } else {
            val intent = Intent(context, ColorizationService::class.java).apply {
                action = ColorizationService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun getInterceptedResponse(url: String): WebResourceResponse? {
        return colorizationManager.getInterceptedResponse(url)
    }
}
