package com.example.mangacolorizer.ui.browse

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

import android.webkit.WebSettings
import com.example.mangacolorizer.utils.Logger

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowseScreen(viewModel: BrowseViewModel, onClose: () -> Unit) {
    val url by viewModel.currentUrl.collectAsState()
    val processingState by viewModel.processingState.collectAsState()
    val isPaused = processingState.processState == com.example.mangacolorizer.data.ProcessState.PAUSED
    val isColorizing = processingState.processState == com.example.mangacolorizer.data.ProcessState.RUNNING
    val processingCount = processingState.pendingCount
    var webView: WebView? by remember { mutableStateOf(null) }

    val isRestoring = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        Logger.i("UI: BrowseScreen mounted")
        onDispose {
            Logger.i("UI: BrowseScreen unmounted - Saving WebView state")
            webView?.let {
                val bundle = android.os.Bundle()
                it.saveState(bundle)
                viewModel.webViewBundle = bundle
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    TextField(
                        value = url,
                        onValueChange = { viewModel.updateUrl(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                    IconButton(onClick = { 
                        Logger.d("UI: Browser Back")
                        isRestoring.value = false
                        webView?.goBack() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = { 
                        Logger.d("UI: Browser Forward")
                        isRestoring.value = false
                        webView?.goForward() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(onClick = { 
                        Logger.d("UI: Browser Reload")
                        isRestoring.value = false
                        webView?.reload() 
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                }
            )

            if (isColorizing || processingCount > 0) {
                Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val progressText = if (processingCount > 0) "Manga AI: $processingCount pages left" else "Finalizing..."
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                                Logger.d("JS Console: [${message?.messageLevel()}] ${message?.message()} (${message?.sourceId()}:${message?.lineNumber()})")
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                Logger.i("UI: Page loading started: $url")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != null) viewModel.updateUrl(url)
                                Logger.i("UI: Page loaded successfully: $url")
                                injectColorizerScript(view)
                            }

                            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                Logger.e("UI: WebView Error: ${error?.description} for ${request?.url}")
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val requestUrl = request?.url?.toString() ?: return null
                                if (requestUrl.startsWith("https://mc-local.com/img")) {
                                    return viewModel.getInterceptedResponse(requestUrl)
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Poco F6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun log(msg: String) {
                                    Logger.d("JS Bridge: $msg")
                                }

                                @JavascriptInterface
                                fun isPaused(): Boolean = viewModel.processingState.value.processState == com.example.mangacolorizer.data.ProcessState.PAUSED || viewModel.processingState.value.processState == com.example.mangacolorizer.data.ProcessState.STOPPING || viewModel.processingState.value.processState == com.example.mangacolorizer.data.ProcessState.IDLE

                                @JavascriptInterface
                                fun onImageDetected(id: String, src: String, referer: String) {
                                    if (viewModel.processingState.value.processState == com.example.mangacolorizer.data.ProcessState.PAUSED || viewModel.processingState.value.processState == com.example.mangacolorizer.data.ProcessState.STOPPING || viewModel.processingState.value.processState == com.example.mangacolorizer.data.ProcessState.IDLE) return
                                    Logger.d("UI: JS found image: $src (ID: $id)")
                                    viewModel.processDetectedImage(id, src, referer) { resultSrc ->
                                        webView?.post {
                                            val encodedSrc = Base64.encodeToString(resultSrc.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                                            val virtualUrl = "https://mc-local.com/img?src=$encodedSrc&t=${System.currentTimeMillis()}"
                                            val js = """
                                            (function() {
                                                try {
                                                    var target = document.getElementById('$id');
                                                    if (target) {
                                                        var virtualUrl = '$virtualUrl';
                                                        if (target.tagName === 'IMG') {
                                                            target.src = virtualUrl;
                                                        } else {
                                                            target.style.backgroundImage = 'url("' + virtualUrl + '")';
                                                            target.style.backgroundSize = 'contain';
                                                            target.style.backgroundRepeat = 'no-repeat';
                                                        }
                                                        target.dataset.processed = 'true';
                                                        target.dataset.colorizing = 'false';
                                                        target.style.filter = 'none';
                                                        target.style.opacity = '1';
                                                        window.MangaColorizer.log('Applied virtual source to: ' + '$id');
                                                    }
                                                } catch (e) {
                                                    window.MangaColorizer.log('JS Error in callback: ' + e.message);
                                                }
                                            })();
                                        """.trimIndent()
                                            webView?.evaluateJavascript(js, null)
                                        }
                                    }
                                }
                            },
                            "MangaColorizer",
                        )
                        
                        val bundle = viewModel.webViewBundle
                        if (bundle != null) {
                            Logger.i("UI: Restoring WebView from saved bundle")
                            isRestoring.value = true
                            restoreState(bundle)
                            viewModel.webViewBundle = null
                            // Re-inject script after some delay to ensure DOM is ready
                            postDelayed({ injectColorizerScript(this@apply) }, 1000)
                        } else {
                            Logger.i("UI: Fresh load for URL: $url")
                            loadUrl(url)
                        }
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                    if (!isRestoring.value && view.url != url && url.isNotBlank()) {
                        Logger.i("UI: Navigation triggered new load: $url")
                        view.loadUrl(url)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Floating Start/Stop Button
        ExtendedFloatingActionButton(
            onClick = { 
                Logger.i("UI: Toggle colorization button pressed. Current state: ${processingState.processState}")
                if (processingState.processState == com.example.mangacolorizer.data.ProcessState.RUNNING) {
                    viewModel.pauseProcessing()
                } else if (processingState.processState == com.example.mangacolorizer.data.ProcessState.PAUSED) {
                    viewModel.resumeProcessing()
                } else {
                    viewModel.startProcessing()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 80.dp, end = 16.dp),
            containerColor = if (isColorizing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (isColorizing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            icon = {
                Icon(
                    imageVector = if (isColorizing) Icons.Default.Refresh else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                )
            },
            text = {
                val text = when (processingState.processState) {
                    com.example.mangacolorizer.data.ProcessState.RUNNING -> "PAUSE PROCESS"
                    com.example.mangacolorizer.data.ProcessState.PAUSED -> "RESUME PROCESS"
                    else -> "START COLORING"
                }
                Text(text)
            }
        )
    }
}

private fun injectColorizerScript(webView: WebView?) {
    val script = """
        (function() {
            window.MangaColorizer.log('Script injected (Durable Engine v10)');
            
            var currentReferer = window.location.href;
            
            function getStableId(s) {
                var hash = 0;
                for (var i = 0; i < s.length; i++) hash = ((hash << 5) - hash) + s.charCodeAt(i) | 0;
                return 'mc_' + Math.abs(hash).toString(36);
            }

            function processImages() {
                var elements = document.querySelectorAll('img, [data-src], [data-lazy-src], [srcset], div[style*="background-image"]');
                
                elements.forEach(function(el, index) {
                    try {
                        var src = el.getAttribute('data-src') || el.getAttribute('data-lazy-src') || el.src;
                        if (!src && el.srcset) src = el.srcset.split(',')[0].trim().split(' ')[0];
                        if (!src && el.tagName !== 'IMG') {
                             var style = window.getComputedStyle(el);
                             var bg = style.backgroundImage;
                             if (bg && bg.startsWith('url(')) src = bg.slice(5, -2).replace(/["']/g, "");
                        }

                        if (!src || src.startsWith('data:') || src.startsWith('blob:') || src.startsWith('https://mc-local.com')) return;
                        
                        if (!src.startsWith('http')) {
                            src = new URL(src, window.location.href).href;
                        }
                        
                        // Ignore small UI icons
                        if (el.tagName === 'IMG' && el.complete && el.naturalWidth > 0 && el.naturalWidth < 150) {
                            el.dataset.processed = 'true';
                            return;
                        }

                        var sid = getStableId(src);
                        if (!el.id || !el.id.startsWith('mc_')) {
                             el.id = sid;
                        }
                        
                        // Skip if currently colorizing or already successfully processed (by looking at dataset)
                        if (el.dataset.processed === 'true' || el.dataset.colorizing === 'true') {
                            // If it's supposedly processed but the src reverted (e.g. from a tab switch or JS framework),
                            // we need to ask the app if it has a cached version.
                            if (el.dataset.processed === 'true' && !src.startsWith('https://mc-local.com')) {
                                el.dataset.processed = 'false'; // force re-check cache
                            } else {
                                return;
                            }
                        }

                        if (window.MangaColorizer.isPaused()) return;

                        el.dataset.colorizing = 'true';
                        el.style.filter = 'sepia(1) hue-rotate(200deg) saturate(300%)';
                        el.style.transition = 'filter 0.5s';
                        
                        window.MangaColorizer.onImageDetected(el.id, src, currentReferer);
                    } catch (e) {
                        el.dataset.processed = 'true';
                    }
                });
            }
            
            // Allow multiple injections to restart processing safely
            if (window.mc_observer) {
                window.mc_observer.disconnect();
            }
            if (window.mc_interval) {
                clearInterval(window.mc_interval);
            }

            window.mc_observer = new MutationObserver(function(mutations) {
                if (window.mc_debounce) clearTimeout(window.mc_debounce);
                window.mc_debounce = setTimeout(processImages, 1000);
            });
            var target = document.body || document.documentElement;
            if (target) {
                window.mc_observer.observe(target, { childList: true, subtree: true });
            }
            
            window.mc_interval = setInterval(processImages, 4000);
            processImages();
        })();
    """.trimIndent()
    webView?.evaluateJavascript(script, null)
}
