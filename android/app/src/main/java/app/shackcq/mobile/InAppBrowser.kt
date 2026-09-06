package app.shackcq.mobile

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.URI

internal fun validatedInAppBrowserUrl(candidate: String): String? = runCatching {
    URI(candidate.trim()).takeIf { uri ->
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }?.toString()
}.getOrNull()

internal fun validatedExternalBrowserUrl(candidate: String): String? = runCatching {
    val uri = URI(candidate.trim())
    when (uri.scheme?.lowercase()) {
        "https", "http" -> uri.takeIf { !it.host.isNullOrBlank() && it.rawUserInfo == null }
        "mailto", "tel" -> uri.takeIf { it.rawSchemeSpecificPart.isNotBlank() }
        else -> null
    }?.toString()
}.getOrNull()

internal enum class InAppBrowserSite { ORDINARY, POTA, SOTA }

internal data class InAppBrowserPolicy(
    val site: InAppBrowserSite,
    val javaScript: Boolean,
    val domStorage: Boolean,
)

internal fun inAppBrowserPolicy(candidate: String): InAppBrowserPolicy? {
    val validated = validatedInAppBrowserUrl(candidate) ?: return null
    val host = URI(validated).host.lowercase()
    return when (host) {
        "pota.app", "www.pota.app" -> InAppBrowserPolicy(InAppBrowserSite.POTA, javaScript = true, domStorage = true)
        "sotadata.org.uk", "www.sotadata.org.uk" -> InAppBrowserPolicy(InAppBrowserSite.SOTA, javaScript = true, domStorage = true)
        else -> InAppBrowserPolicy(InAppBrowserSite.ORDINARY, javaScript = false, domStorage = false)
    }
}

private fun WebView.applyBrowserPolicy(policy: InAppBrowserPolicy) {
    settings.javaScriptEnabled = policy.javaScript
    settings.domStorageEnabled = policy.domStorage
    CookieManager.getInstance().apply {
        setAcceptThirdPartyCookies(this@applyBrowserPolicy, false)
    }
}

@Stable
internal class InAppBrowserState {
    var url by mutableStateOf<String?>(null)
        private set
    fun open(candidate: String) { url = validatedInAppBrowserUrl(candidate) }
    fun close() { url = null }
}

internal val LocalInAppBrowserState = staticCompositionLocalOf<InAppBrowserState?> { null }

@Composable
internal fun rememberInAppBrowserState(): InAppBrowserState = remember { InAppBrowserState() }

@Composable
internal fun InAppBrowserDialog(state: InAppBrowserState) {
    val url = state.url ?: return
    val context = LocalContext.current
    var webView by remember(url) { mutableStateOf<WebView?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var currentUrl by remember(url) { mutableStateOf(url) }
    var pageTitle by remember(url) { mutableStateOf("") }
    var canGoBack by remember(url) { mutableStateOf(false) }
    var canGoForward by remember(url) { mutableStateOf(false) }
    var pendingExternalUrl by remember(url) { mutableStateOf<String?>(null) }
    var renderError by remember(url) { mutableStateOf<String?>(null) }
    val callbackLifecycle = remember(url) { LifecycleGeneration() }
    val callbackGeneration = remember(url) { callbackLifecycle.next() }
    val launchPolicy = inAppBrowserPolicy(url) ?: return
    Dialog(onDismissRequest = state::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).fillMaxHeight(.90f), shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ webView?.goBack() }, enabled = canGoBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    IconButton({ webView?.goForward() }, enabled = canGoForward) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Forward") }
                    IconButton({ webView?.reload() }) { Icon(Icons.Outlined.Refresh, "Reload") }
                    Column(Modifier.weight(1f)) {
                        Text(pageTitle.ifBlank { "Secure browser" }, maxLines = 1)
                        Text(Uri.parse(currentUrl).host.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    TextButton({
                        validatedInAppBrowserUrl(currentUrl)?.let {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(5.dp)); Text("EXTERNAL")
                    }
                    IconButton(state::close) { Icon(Icons.Outlined.Close, "Close") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { browserContext ->
                        WebView(browserContext).apply {
                        applyBrowserPolicy(launchPolicy)
                        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (!callbackLifecycle.isCurrent(callbackGeneration)) return true
                                val target = request?.url?.toString().orEmpty()
                                val targetPolicy = inAppBrowserPolicy(target)
                                if (targetPolicy != null) {
                                    val currentPolicy = inAppBrowserPolicy(currentUrl) ?: launchPolicy
                                    if (currentPolicy.javaScript && targetPolicy.site != currentPolicy.site) {
                                        pendingExternalUrl = validatedExternalBrowserUrl(target)
                                        return true
                                    }
                                    view?.applyBrowserPolicy(targetPolicy)
                                    return false
                                }
                                pendingExternalUrl = validatedExternalBrowserUrl(target)
                                return true
                            }
                            override fun onPageStarted(view: WebView?, target: String?, favicon: android.graphics.Bitmap?) {
                                if (!callbackLifecycle.isCurrent(callbackGeneration)) return
                                loading = true
                                renderError = null
                                validatedInAppBrowserUrl(target.orEmpty())?.let {
                                    currentUrl = it
                                    inAppBrowserPolicy(it)?.let { policy -> view?.applyBrowserPolicy(policy) }
                                }
                            }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (!callbackLifecycle.isCurrent(callbackGeneration)) return
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    renderError = error?.description?.toString()?.takeIf(String::isNotBlank)
                                        ?: "The page could not be rendered securely."
                                }
                            }
                            override fun onPageFinished(view: WebView?, target: String?) {
                                if (!callbackLifecycle.isCurrent(callbackGeneration)) return
                                loading = false
                                validatedInAppBrowserUrl(target.orEmpty())?.let { currentUrl = it }
                                pageTitle = view?.title.orEmpty()
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }
                        }
                        setDownloadListener { target, _, _, _, _ ->
                            if (callbackLifecycle.isCurrent(callbackGeneration)) {
                                pendingExternalUrl = validatedExternalBrowserUrl(target)
                            }
                        }
                        webView = this
                        loadUrl(url)
                        }
                    }, update = { webView = it }, modifier = Modifier.fillMaxSize())
                    renderError?.let { error ->
                        Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 8.dp) {
                            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PAGE UNAVAILABLE", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(error, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(10.dp))
                                Button({
                                    validatedExternalBrowserUrl(currentUrl)?.let {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                                    }
                                }) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(5.dp)); Text("OPEN EXTERNALLY") }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingExternalUrl?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingExternalUrl = null },
            title = { Text("Open outside ShackCQ?") },
            text = { Text(target) },
            confirmButton = {
                Button({
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    pendingExternalUrl = null
                }) { Text("OPEN EXTERNALLY") }
            },
            dismissButton = { TextButton({ pendingExternalUrl = null }) { Text("CANCEL") } },
        )
    }
    DisposableEffect(url) {
        onDispose {
            callbackLifecycle.close()
            webView?.let { retiring ->
                retiring.stopLoading()
                retiring.webViewClient = WebViewClient()
                retiring.setDownloadListener(null)
                (retiring.parent as? ViewGroup)?.removeView(retiring)
                retiring.removeAllViews()
                retiring.destroy()
            }
            webView = null
        }
    }
}
