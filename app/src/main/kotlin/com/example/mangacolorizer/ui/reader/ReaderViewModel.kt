package com.example.mangacolorizer.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.mangacolorizer.inference.ColorizationCache
import com.example.mangacolorizer.inference.ColorizationResult
import com.example.mangacolorizer.inference.MangaColorizer
import com.example.mangacolorizer.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
    private val cache: ColorizationCache,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _pages = MutableStateFlow<List<PageState>>(emptyList())
    val pages = _pages.asStateFlow()

    private val processingJobs = mutableMapOf<Int, Job>()

    init {
        val restoredUris = savedStateHandle.get<List<String>>("loaded_uris")
        if (restoredUris != null) {
            Logger.i("ReaderViewModel: Restoring ${restoredUris.size} pages from SavedStateHandle")
            loadPages(restoredUris)
        }
    }

    fun loadPages(uris: List<String>) {
        Logger.i("ReaderViewModel: Loading ${uris.size} pages")
        savedStateHandle["loaded_uris"] = uris
        _pages.value = uris.map { uri ->
            PageState(uri, colorizedBitmap = cache.get(uri))
        }
    }

    fun clearPages() {
        Logger.i("ReaderViewModel: Clearing all pages and stopping jobs")
        savedStateHandle.remove<List<String>>("loaded_uris")
        _pages.value = emptyList()
        processingJobs.values.forEach { it.cancel() }
        processingJobs.clear()
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

        Logger.d("ReaderViewModel: Starting colorization for page $index (${currentPage.originalUri})")

        processingJobs[index] = viewModelScope.launch {
            updatePageState(index) { it.copy(isProcessing = true) }
            
            val bitmapToProcess = originalBitmap ?: fetchBitmap(currentPage.originalUri)
            
            val result = if (bitmapToProcess != null) {
                withContext(Dispatchers.Default) {
                    try {
                        val colorizeResult = colorizer.colorize(bitmapToProcess)
                        when (colorizeResult) {
                            is ColorizationResult.Success -> {
                                Logger.d("ReaderViewModel: Successfully colorized page $index")
                                cache.put(currentPage.originalUri, colorizeResult.bitmap)
                                colorizeResult.bitmap
                            }
                            is ColorizationResult.Skipped -> {
                                Logger.d("ReaderViewModel: Skipped colorization for page $index (already colored)")
                                cache.put(currentPage.originalUri, colorizeResult.bitmap)
                                colorizeResult.bitmap
                            }
                            is ColorizationResult.Error -> {
                                Logger.e("ReaderViewModel: Colorization failed for page $index", colorizeResult.exception)
                                null
                            }
                        }
                    } catch (e: CancellationException) {
                        Logger.i("ReaderViewModel: Colorization cancelled for page $index")
                        throw e
                    } catch (e: Exception) {
                        Logger.e("ReaderViewModel: Colorization crashed for page $index", e)
                        null
                    }
                }
            } else {
                 Logger.e("ReaderViewModel: Failed to fetch bitmap for page $index")
                 null
            }

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
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            (result.drawable as? BitmapDrawable)?.bitmap
        } catch (e: Exception) {
            Logger.e("ReaderViewModel: fetchBitmap failed for $uri", e)
            null
        }
    }

    private fun updatePageState(index: Int, transform: (PageState) -> PageState) {
        val newList = _pages.value.toMutableList()
        if (index in newList.indices) {
            newList[index] = transform(newList[index])
            _pages.value = newList
        }
    }
}
