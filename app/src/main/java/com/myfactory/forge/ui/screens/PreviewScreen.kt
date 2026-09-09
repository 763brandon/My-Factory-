package com.myfactory.forge.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.myfactory.forge.R
import com.myfactory.forge.core.capability.Capabilities
import com.myfactory.forge.ui.components.EmptyState

/**
 * Sandboxed preview of a local dev server.
 *
 * The WebView here is locked down harder than a browser's:
 *   - It will only navigate to loopback. A page that redirects to the open
 *     internet is blocked, not followed.
 *   - File and content URL access are off, so a page cannot read the app's
 *     own storage through file://.
 *   - No JavaScript bridge is installed, so page script has no route into
 *     the app process.
 *
 * JavaScript itself is on, because a preview that cannot run scripts is not a
 * preview of a web app.
 */
@Composable
fun PreviewScreen(
    capabilities: Capabilities,
    initialUrl: String,
    servingUrl: String?,
    onStartServing: () -> Unit,
    onStopServing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!capabilities.webPreviewEnabled) {
        EmptyState(
            icon = Icons.Default.Web,
            title = stringResource(R.string.preview_unavailable_title),
            body = capabilities.reasons.firstOrNull { it.contains("Web preview") }
                ?: stringResource(R.string.preview_unavailable_title),
            modifier = modifier,
        )
        return
    }

    var address by remember { mutableStateOf(servingUrl ?: initialUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var blockedNotice by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            blockedNotice = false
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.preview_url_hint)) },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            if (isLoopback(address)) {
                                loadError = null
                                webView?.loadUrl(address)
                            } else {
                                blockedNotice = true
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.preview_refresh),
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (servingUrl == null) {
                        Button(onClick = onStartServing) {
                            Text(stringResource(R.string.preview_serve_project))
                        }
                    } else {
                        Button(onClick = onStopServing) {
                            Text(stringResource(R.string.preview_stop_serving))
                        }
                        Text(
                            text = stringResource(R.string.preview_serving_at, servingUrl),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                if (blockedNotice) {
                    Text(
                        text = stringResource(R.string.preview_only_local),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                loadError?.let {
                    Text(
                        text = stringResource(R.string.preview_load_failed, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    configureSandbox(settings)
                    webViewClient = LoopbackOnlyClient(
                        onBlocked = { blockedNotice = true },
                        onError = { loadError = it },
                    )
                    if (isLoopback(address)) loadUrl(address)
                }
            },
            update = { view -> webView = view },
            // Releasing the renderer when the pane leaves the tree matters on
            // a 2 GB device, where a live Chromium process is a large share of
            // the memory budget.
            onRelease = { view ->
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
                webView = null
            },
        )
    }
}

// Scripting is on deliberately: a preview that cannot run JavaScript is not
// a preview of a web app. The exposure is bounded by everything below it plus
// the loopback-only navigation guard, so the page can only ever be one the
// user is already serving from their own device.
@SuppressLint("SetJavaScriptEnabled")
private fun configureSandbox(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true

    // Nothing the page does may reach the filesystem or the content provider.
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = false

    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setGeolocationEnabled(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.databaseEnabled = false
    settings.mediaPlaybackRequiresUserGesture = true

    // A dev server should always be served fresh.
    settings.cacheMode = WebSettings.LOAD_NO_CACHE

    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}

/** Refuses to leave the device, however the page tries. */
private class LoopbackOnlyClient(
    private val onBlocked: () -> Unit,
    private val onError: (String) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val url = request?.url?.toString() ?: return true
        if (isLoopback(url)) return false
        onBlocked()
        return true // blocked: do not navigate
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) {
            onError(request.url?.toString() ?: "the page")
        }
    }
}

/**
 * True only for http or https on this device.
 *
 * Checked against the parsed host rather than by string prefix, so
 * `http://127.0.0.1.evil.example/` does not slip through.
 */
internal fun isLoopback(url: String): Boolean = runCatching {
    val uri = android.net.Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    when (uri.host?.lowercase()) {
        "127.0.0.1", "localhost", "::1", "[::1]" -> true
        else -> false
    }
}.getOrDefault(false)
