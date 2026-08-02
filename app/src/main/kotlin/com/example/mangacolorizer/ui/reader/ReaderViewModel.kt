package com.example.mangacolorizer.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.mangacolorizer.inference.ColorizationCache
import com.example.mangacolorizer.inference.MangaColorizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PageState(
    val originalUri: String,
    val colorizedBitmap: Bitmap? = null,
    val isProcessing: Boolean = false
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val colorizer: MangaColorizer,
    private val cache: ColorizationCache
) : ViewModel() {

    private val _pages = MutableStateFlow<List<PageState>>(emptyList())
    val pages = _pages.asStateFlow()

    private val processingJobs = mutableMapOf<Int, Job>()

    fun loadPages(uris: List<String>) {
        _pages.value = uris.map { uri ->
            PageState(uri, colorizedBitmap = cache.get(uri))
        }
    }

    fun onPageVisible(index: Int) {
        // Trigger colorization for current page
        colorizePage(index)
        
        // Prefetch next 2 pages
        prefetchPage(index + 1)
        prefetchPage(index + 2)
    }

    fun colorizePage(index: Int, originalBitmap: Bitmap? = null) {
        val currentPage = _pages.value.getOrNull(index) ?: return
        if (currentPage.colorizedBitmap != null || currentPage.isProcessing) return

        processingJobs[index] = viewModelScope.launch {
            updatePageState(index) { it.copy(isProcessing = true) }
            
            val bitmapToProcess = originalBitmap ?: fetchBitmap(currentPage.originalUri)
            
            val result = if (bitmapToProcess != null) {
                withContext(Dispatchers.Default) {
                    try {
                        val processed = colorizer.colorize(bitmapToProcess)
                        cache.put(currentPage.originalUri, processed)
                        processed
                    } catch (e: Exception) {
                        android.util.Log.e("ReaderViewModel", "Colorization failed: ${e.message}")
                        null
                    }
                }
            } else null

            updatePageState(index) { it.copy(colorizedBitmap = result, isProcessing = false) }
            processingJobs.remove(index)
        }
    }

    private fun prefetchPage(index: Int) {
        val page = _pages.value.getOrNull(index) ?: return
        if (page.colorizedBitmap != null || page.isProcessing) return
        colorizePage(index)
    }

    private suspend fun fetchBitmap(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(uri)
            .allowHardware(false)
            .build()
        val result = loader.execute(request)
        (result.drawable as? BitmapDrawable)?.bitmap
    }

    private fun updatePageState(index: Int, transform: (PageState) -> PageState) {
        val newList = _pages.value.toMutableList()
        if (index in newList.indices) {
            newList[index] = transform(newList[index])
            _pages.value = newList
        }
    }
}
