package com.example.mangacolorizer.ui.reader

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val pages by viewModel.pages.collectAsState()
    val pagerState = rememberPagerState(pageCount = { pages.size })
    var showOriginal by remember { mutableStateOf(false) }
    
    val processingCount = pages.count { it.isProcessing }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            beyondViewportPageCount = 2
        ) { index ->
            MangaPageItem(
                pageState = pages[index],
                showOriginal = showOriginal,
                onOriginalLoaded = { bitmap ->
                    viewModel.colorizePage(index, bitmap)
                }
            )
        }

        if (processingCount > 0) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // Overlay controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FilledTonalIconToggleButton(
                checked = showOriginal,
                onCheckedChange = { showOriginal = it }
            ) {
                Text(if (showOriginal) "BW" else "CLR")
            }
        }
    }
}

@Composable
fun MangaPageItem(
    pageState: PageState,
    showOriginal: Boolean,
    onOriginalLoaded: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val zoomableState = rememberZoomableState()
    
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(pageState.originalUri)
            .allowHardware(false)
            .build()
    )

    val painterState = painter.state
    if (painterState is AsyncImagePainter.State.Success) {
        val bitmap = (painterState.result.drawable as BitmapDrawable).bitmap
        LaunchedEffect(bitmap) {
            onOriginalLoaded(bitmap)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zoomable(zoomableState),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            alpha = if (showOriginal || pageState.colorizedBitmap == null) 1f else 0f
        )

        if (!showOriginal) {
            pageState.colorizedBitmap?.let { colorized ->
                Image(
                    bitmap = colorized.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        if (pageState.isProcessing && !showOriginal) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp)
            )
        }
    }
}
