package com.example.mangacolorizer.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mangacolorizer.data.ProcessState
import com.example.mangacolorizer.inference.ColorizationManager
import com.example.mangacolorizer.service.ColorizationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val colorizationManager: ColorizationManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _currentUrl = MutableStateFlow("https://www.google.com")
    val currentUrl = _currentUrl.asStateFlow()

    val processingState = colorizationManager.processingState

    var webViewBundle: android.os.Bundle? = null

    private var previousState: ProcessState? = null

    init {
        if (colorizationManager.processingState.value.processState == ProcessState.RUNNING) {
            updateServiceState(colorizationManager.processingState.value.processState)
            previousState = ProcessState.RUNNING
        }

        viewModelScope.launch {
            colorizationManager.processingState
                .map { it.processState }
                .distinctUntilChanged()
                .collect { state ->
                    if (previousState != null || state == ProcessState.RUNNING) {
                        updateServiceState(state)
                    }
                    previousState = state
                }
        }
    }

    fun updateUrl(url: String) {
        if (_currentUrl.value != url) {
            _currentUrl.value = url
        }
    }

    fun startProcessing() {
        colorizationManager.startProcessing()
    }

    fun stopProcessing() {
        colorizationManager.stopProcessing()
    }

    fun processDetectedImage(id: String, src: String, referer: String, onComplete: (String) -> Unit) {
        colorizationManager.addImage(id, src, referer, onComplete)
    }

    private fun updateServiceState(state: ProcessState) {
        if (state == ProcessState.IDLE) {
            val intent = Intent(context, ColorizationService::class.java).apply {
                action = ColorizationService.ACTION_STOP
            }
            context.startService(intent)
        } else if (state == ProcessState.RUNNING) {
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
