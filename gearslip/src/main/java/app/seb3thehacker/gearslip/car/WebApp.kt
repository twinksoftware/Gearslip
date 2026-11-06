package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.car.theme.gsColors
import androidx.compose.runtime.LaunchedEffect
import app.seb3thehacker.gearslip.car.theme.GsIconButton
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import app.seb3thehacker.gearslip.GearslipLog
import org.json.JSONObject
import android.graphics.Bitmap
import androidx.car.app.CarToast
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import app.seb3thehacker.gearslip.browser.BrowserSettings
import app.seb3thehacker.gearslip.browser.SiteIcons
import app.seb3thehacker.gearslip.browser.adblock.AdBlockManager
import app.seb3thehacker.gearslip.browser.adblock.CosmeticFilterBridge
import app.seb3thehacker.gearslip.browser.adblock.YouTubeAdBlock
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ArrowForward

/**
 * A browser inside the car UI. [content] is a URL, or raw HTML when it does not look like one;
 * blank opens [BrowserStartPage], the search bar and bookmark tiles. The bottom bar's Back walks
 * the page history first, then returns to the start page, and only then leaves the app.
 *
 * Every request a page makes passes [AdBlockManager] first, and [CosmeticFilterBridge] hides the
 * ad slots that are left (issue #85). Both are switched from the phone's browser settings.
 *
 * A page's own text inputs can't call up the phone's IME: the car UI lives in a Presentation on
 * a virtual display, and the platform's soft keyboard has nowhere sensible to appear there (see
 * [CarKeyboard]'s doc comment - every other text field in this app already draws its own for the
 * same reason). [FOCUS_TRACKING_JS] reports focus/blur on the page's editable elements, and
 * [CarKeyboard] edits the focused one back through injected JS instead of through the DOM's own
 * native input path, which this Presentation can't reach.
 */
@Composable
fun WebApp(content: String) {
    val navigator = LocalCarNavigator.current
    val darkOutside = LocalDarkOutside.current
    val ring = String.format("#%06X", MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)
    var web by remember { mutableStateOf<WebView?>(null) }
    var fieldText by remember { mutableStateOf<String?>(null) }
    // A page's own full screen, such as a video's: the view to show, and how to hand it back.
    var pageFullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }
    // Whether the video asked for full screen, rather than the driver from the header: ending
    // the video's then leaves full screen too, instead of stranding them in it.
    var videoMadeFullscreen by remember { mutableStateOf(false) }
    val fullscreen = CarFullscreen.active
    // What the header shows and what its buttons can do, refreshed as pages load.
    var pageTitle by remember { mutableStateOf("") }
    var canGoForward by remember { mutableStateOf(false) }
    // The address being typed, or null while the header shows the page title.
    var address by remember { mutableStateOf<String?>(null) }
    // An address being typed to save as a bookmark, from the start page's Add tile.
    var newBookmark by remember { mutableStateOf<String?>(null) }
    // The start page covers the WebView until something is opened.
    var atStart by remember { mutableStateOf(content.isBlank()) }
    var pageUrl by remember { mutableStateOf<String?>(null) }
    val bookmarks by BrowserSettings.bookmarks.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AdBlockManager.ensureInitialized(context)
        AdBlockManager.refreshIfDue(context)
    }
    val open: (String) -> Unit = { url ->
        atStart = false
        web?.loadUrl(url)
    }
    val goHome: () -> Unit = {
        if (content.isBlank()) {
            atStart = true
            pageTitle = ""
        } else {
            web?.let { view -> loadContent(view, content) }
        }
    }

    // Leaving the browser ends full screen, whoever started it.
    DisposableEffect(Unit) { onDispose { CarFullscreen.exit() } }
    // The way out of full screen also ends the page's.
    LaunchedEffect(fullscreen) {
        if (!fullscreen) pageFullscreen?.let { (_, callback) ->
            pageFullscreen = null
            callback.onCustomViewHidden()
        }
    }

    DisposableEffect(web, atStart) {
        navigator.backInterceptor = {
            val view = web
            if (CarFullscreen.active) {
                CarFullscreen.exit()
                true
            } else if (!atStart && view != null && view.canGoBack()) {
                view.goBack()
                true
            } else if (!atStart && content.isBlank()) {
                goHome()
                true
            } else {
                false
            }
        }
        onDispose { navigator.backInterceptor = null }
    }

    Column(Modifier.fillMaxSize()) {
        // Back steps through the page's history first (the interceptor above), then leaves.
        if (!fullscreen) {
            ScreenHeader(pageTitle.takeUnless { atStart }.orEmpty().ifEmpty { stringResource(R.string.app_browser) }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val url = pageUrl
                if (!atStart && url != null && url.startsWith("http")) {
                    val saved = remember(bookmarks, url) { BrowserSettings.isBookmarked(url) }
                    GsIconBox(
                        {
                            if (saved) BrowserSettings.removeBookmark(url) else BrowserSettings.addBookmark(pageTitle, url)
                            CarToasts.show(BrowserSettings, context.getString(if (saved) R.string.web_bookmark_removed else R.string.web_bookmark_added), CarToast.LENGTH_SHORT)
                        },
                        Modifier.size(48.dp),
                        gsColors(if (saved) GsTone.Tonal else GsTone.Neutral),
                        latched = saved,
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.Star,
                            stringResource(if (saved) R.string.web_remove_this_bookmark else R.string.web_bookmark_this),
                            Modifier.size(24.dp),
                            tint = if (saved) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.6f),
                        )
                    }
                }
                GsIconButton(androidx.compose.material.icons.Icons.Filled.Search, stringResource(R.string.web_search), {
                    fieldText = null
                    address = ""
                })
                if (canGoForward && !atStart) {
                    GsIconButton(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.web_forward), { web?.goForward() })
                }
                if (!atStart) {
                    GsIconButton(androidx.compose.material.icons.Icons.Filled.Refresh, stringResource(R.string.web_reload), { web?.reload() })
                    GsIconButton(androidx.compose.material.icons.Icons.Filled.Home, stringResource(R.string.web_start_page), goHome)
                    GsIconButton(MediaIcons.Fullscreen, stringResource(R.string.web_fullscreen), CarFullscreen::enter)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    // The page a request belongs to, for telling first-party requests from
                    // third-party ones: shouldInterceptRequest runs off the UI thread, where a
                    // WebView's own url can't be read.
                    val documentUrl = AtomicReference<String?>(null)
                    CosmeticFilterBridge.attach(this)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = LoggingClient(ring, documentUrl, onStarted = { pageUrl = it }) { view ->
                        canGoForward = view.canGoForward()
                        pageUrl = view.url
                        pageTitle = view.title.orEmpty().takeUnless { it.startsWith("http") || it == "about:blank" }.orEmpty()
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            pageTitle = title.orEmpty().takeUnless { it.startsWith("http") || it == "about:blank" }.orEmpty()
                        }

                        // Kept for the start page's tiles, so a bookmark shows its own icon.
                        override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                            val host = view?.url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }?.removePrefix("www.")
                            if (icon != null && host != null) SiteIcons.save(view.context, host, icon)
                        }

                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            videoMadeFullscreen = !CarFullscreen.active
                            pageFullscreen = view to callback
                            CarFullscreen.enter()
                        }

                        override fun onHideCustomView() {
                            if (pageFullscreen == null) return
                            pageFullscreen = null
                            if (videoMadeFullscreen) CarFullscreen.exit()
                        }
                    }
                    addJavascriptInterface(
                        FieldFocusBridge(onFocus = { fieldText = it }, onBlur = { fieldText = null }),
                        "GearslipField",
                    )
                    if (content.isNotBlank()) loadContent(this, content)
                    web = this
                }
            },
            // Pages that honour it follow the darkness outside; a first, tweakable use of the signal.
            update = { it.settings.isAlgorithmicDarkeningAllowed = darkOutside },
            onRelease = { it.destroy() },
        )
        if (atStart) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                BrowserStartPage(
                    onOpen = open,
                    onSearch = {
                        fieldText = null
                        address = ""
                    },
                    onAdd = { newBookmark = "https://" },
                )
            }
        }
        pageFullscreen?.let { (view, _) ->
            androidx.compose.runtime.key(view) {
                AndroidView(
                    factory = { context ->
                        FrameLayout(context).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            (view.parent as? ViewGroup)?.removeView(view)
                            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.removeAllViews() },
                )
            }
        }
        }
        address?.let { typed ->
            CarKeyboard(
                text = typed,
                onTextChange = { address = it },
                onSubmit = {
                    open(BrowserSettings.addressToUrl(typed))
                    address = null
                },
                onDismiss = { address = null },
            )
        }
        newBookmark?.let { typed ->
            CarKeyboard(
                text = typed,
                onTextChange = { newBookmark = it },
                onSubmit = {
                    // Only an address: words typed here would otherwise save a search as a bookmark.
                    val text = typed.trim()
                    val url = when {
                        text.startsWith("http://") || text.startsWith("https://") -> text
                        ' ' !in text && '.' in text -> "https://$text"
                        else -> null
                    }
                    if (url != null && BrowserSettings.addBookmark("", url)) {
                        CarToasts.show(BrowserSettings, context.getString(R.string.web_bookmark_added), CarToast.LENGTH_SHORT)
                    }
                    newBookmark = null
                },
                onDismiss = { newBookmark = null },
            )
        }
        fieldText?.let { text ->
            CarKeyboard(
                text = text,
                onTextChange = { updated ->
                    fieldText = updated
                    web?.evaluateJavascript(setFieldValueJs(updated), null)
                },
                onSubmit = { web?.evaluateJavascript(SUBMIT_FIELD_JS, null) },
                onDismiss = {
                    fieldText = null
                    web?.evaluateJavascript(BLUR_FIELD_JS, null)
                },
            )
        }
    }
}

/** The start page: a web address, or a page of HTML Gearslip made itself. */
private fun loadContent(view: WebView, content: String) {
    if (content.startsWith("http://") || content.startsWith("https://")) {
        view.loadUrl(content)
    } else {
        view.loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
    }
}

private class LoggingClient(
    private val ring: String,
    private val documentUrl: AtomicReference<String?>,
    private val onStarted: (String?) -> Unit,
    private val onLoaded: (WebView) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
        request?.let { AdBlockManager.response(it, documentUrl.get()) }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        documentUrl.set(url)
        onStarted(url)
        // Without document-start scripts, inject as early as this WebView allows.
        if (CosmeticFilterBridge.needsPageStartFallback) {
            view?.evaluateJavascript(CosmeticFilterBridge.INJECTION_JS, null)
            view?.evaluateJavascript(YouTubeAdBlock.INJECTION_JS, null)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        documentUrl.set(url)
        GearslipLog.i("car web page loaded: $url")
        view?.let(onLoaded)
        view?.evaluateJavascript(FOCUS_TRACKING_JS, null)
        view?.evaluateJavascript(focusRingJs(ring), null)
    }

    // onPageFinished fires for error pages too, so without this a blocked load (missing
    // INTERNET permission, no network) looks like a successful one.
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) {
            GearslipLog.e("car web page FAILED: ${request.url} - ${error?.errorCode} ${error?.description}")
        }
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
        if (request?.isForMainFrame == true) {
            GearslipLog.w("car web page HTTP ${response?.statusCode} for ${request.url}")
        }
    }
}

/** Tells Kotlin when an editable element on the page gains or loses focus, so [CarKeyboard] can show. */
private class FieldFocusBridge(private val onFocus: (String) -> Unit, private val onBlur: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    // WebView calls into this from its own thread, never Compose's.
    @JavascriptInterface
    fun focused(value: String) { main.post { onFocus(value) } }

    @JavascriptInterface
    fun blurred() { main.post { onBlur() } }
}

// Plain `el.value = x` never reaches a React-controlled input: React replaces the native value
// setter with its own to track edits, so a direct assignment sets the DOM attribute but leaves
// React's own state (and anything bound to it, like a search box's query) unaware. Calling the
// *native* setter through the prototype, the same trick browser extensions use, is what actually
// notifies it once the 'input' event fires.
private fun setFieldValueJs(value: String) = """
    (function() {
        var el = document.activeElement;
        if (!el) return;
        var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
        var setter = Object.getOwnPropertyDescriptor(proto, 'value');
        if (setter && setter.set) { setter.set.call(el, ${JSONObject.quote(value)}); } else { el.value = ${JSONObject.quote(value)}; }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    })();
""".trimIndent()

// Fires a real Enter keydown first - the same event a site's own JS would see from a physical
// keyboard - and only falls back to submitting the form directly if nothing handled it, so a
// search-as-you-type page and a plain <form> both work without submitting twice.
// Dismissing the host keyboard without blurring the page's own field would leave it focused,
// so the next tap anywhere with a text cursor pops the keyboard straight back up.
private const val BLUR_FIELD_JS = "document.activeElement && document.activeElement.blur();"

private const val SUBMIT_FIELD_JS = """
    (function() {
        var el = document.activeElement;
        if (!el) return;
        var enter = new KeyboardEvent('keydown', {
            key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true,
        });
        var notHandled = el.dispatchEvent(enter);
        if (notHandled && el.form) {
            if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
        }
    })();
"""

/**
 * The same ring the rest of the car UI shows, on whatever link or field the knob is on. Many
 * sites hide the browser's own focus outline, which leaves a knob driver with no idea where they are.
 */
private fun focusRingJs(color: String) = """
    (function() {
        if (document.getElementById('gearslip-focus-ring')) return;
        var style = document.createElement('style');
        style.id = 'gearslip-focus-ring';
        style.textContent = ':focus-visible { outline: 3px solid $color !important; outline-offset: 2px !important; }';
        (document.head || document.documentElement).appendChild(style);
    })();
"""

/** Reports focus/blur on text inputs, textareas and contenteditable elements to [FieldFocusBridge]. */
private const val FOCUS_TRACKING_JS = """
    (function() {
        function isEditable(el) {
            if (!el) return false;
            var tag = el.tagName;
            if (tag === 'TEXTAREA') return true;
            if (el.isContentEditable) return true;
            if (tag !== 'INPUT') return false;
            var skip = ['button', 'submit', 'checkbox', 'radio', 'range', 'color', 'file', 'image', 'reset'];
            return skip.indexOf((el.type || 'text').toLowerCase()) === -1;
        }
        document.addEventListener('focusin', function(e) {
            if (isEditable(e.target)) window.GearslipField.focused(e.target.value || '');
        });
        document.addEventListener('focusout', function(e) {
            if (isEditable(e.target)) window.GearslipField.blurred();
        });
    })();
"""
