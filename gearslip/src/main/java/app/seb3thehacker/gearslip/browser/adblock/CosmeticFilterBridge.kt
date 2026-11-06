package app.seb3thehacker.gearslip.browser.adblock

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Applies element-hiding rules to a page.
 *
 * Which rules apply depends on the page's hostname and on what is actually in its DOM, neither
 * of which is known until the document starts loading, so the page fetches its own stylesheet
 * through a JavaScript bridge. The bridge exposes two pure functions that return CSS: they read
 * no page state and perform no action, so handing them to untrusted page content costs nothing.
 *
 * Hiding happens in two passes. At document start the page gets the rules written for its own
 * host, which must land before anything renders — injecting on page finish would let ads appear
 * and then vanish, which is worse than not hiding them. The generic rules follow once the DOM
 * exists, keyed on the classes and ids actually present, because shipping all of them up front
 * would mean roughly 400 KB of CSS per page.
 */
object CosmeticFilterBridge {

    const val BRIDGE_OBJECT_NAME = "GearslipContentBlocker"

    /** Stop watching for new elements after this long, to spare the CPU on a head unit. */
    private const val OBSERVE_DURATION_MS = 30000

    /** Upper bound on how many new class/id names are sent to the bridge in one pass. */
    private const val MAX_TOKENS_PER_PASS = 4000

    /** True when document-start injection is unavailable and the client must inject later. */
    val needsPageStartFallback: Boolean
        get() = !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    val INJECTION_JS: String = """
        (function () {
          try {
            if (window.__gearslipCosmetic) { return; }
            var bridge = window.$BRIDGE_OBJECT_NAME;
            if (!bridge || typeof bridge.cssForHost !== 'function') { return; }

            var host = location.hostname;
            var seenClass = Object.create(null);
            var seenId = Object.create(null);
            window.__gearslipCosmetic = true;

            function addStyle(css) {
              if (!css) { return; }
              var style = document.createElement('style');
              style.setAttribute('data-gearslip', 'content-blocker');
              style.textContent = css;
              var root = document.head || document.documentElement;
              if (root) { root.appendChild(style); }
            }

            // The engine loads from disk in the background and may not be ready when the
            // first page starts. Keep asking until it answers, so a page that loses the race
            // still gets its hiding rules rather than none at all.
            var hostCssApplied = false;
            function applyHostCss() {
              if (hostCssApplied) { return true; }
              var css = bridge.cssForHost(host);
              if (!css) { return false; }
              hostCssApplied = true;
              addStyle(css);
              return true;
            }
            applyHostCss();
            var retries = [250, 750, 1500, 3000, 5000, 8000, 12000];
            for (var r = 0; r < retries.length; r++) {
              setTimeout(function () {
                try {
                  if (!hostCssApplied && applyHostCss()) { collect(); }
                } catch (e) {}
              }, retries[r]);
            }

            var classes = [];
            var ids = [];

            function scan(root, budget) {
              if (!root || root.nodeType !== 1) { return budget; }
              var elements = root.getElementsByTagName('*');
              var total = elements.length + 1;
              for (var i = 0; i < total && budget > 0; i++) {
                var element = (i === 0) ? root : elements[i - 1];
                var id = element.id;
                if (id && !seenId[id]) { seenId[id] = true; ids.push(id); budget--; }
                // SVG elements expose className as an SVGAnimatedString, not a string.
                var names = element.className;
                if (names && typeof names === 'string') {
                  var parts = names.split(/\s+/);
                  for (var j = 0; j < parts.length && budget > 0; j++) {
                    var name = parts[j];
                    if (name && !seenClass[name]) {
                      seenClass[name] = true;
                      classes.push(name);
                      budget--;
                    }
                  }
                }
              }
              return budget;
            }

            function collect() {
              classes.length = 0;
              ids.length = 0;
              var budget = $MAX_TOKENS_PER_PASS;
              if (!scannedOnce) {
                scannedOnce = true;
                budget = scan(document.documentElement, budget);
              } else {
                // Only look at what the page actually added. Re-walking the whole document
                // on every mutation costs far more than it finds: on a large single-page app
                // it means a full DOM traversal every 50ms while the page is starting up.
                for (var i = 0; i < pending.length && budget > 0; i++) {
                  budget = scan(pending[i], budget);
                }
              }
              pending.length = 0;
              if (classes.length || ids.length) {
                addStyle(bridge.cssForTokens(host, classes.join(' '), ids.join(' ')));
              }
            }

            var scannedOnce = false;
            var pending = [];
            var scheduled = false;
            function schedule() {
              if (scheduled) { return; }
              scheduled = true;
              setTimeout(function () {
                scheduled = false;
                try { collect(); } catch (e) {}
              }, 50);
            }

            if (document.readyState === 'loading') {
              document.addEventListener('DOMContentLoaded', schedule);
            } else {
              schedule();
            }

            if (typeof MutationObserver === 'function' && document.documentElement) {
              var observer = new MutationObserver(function (records) {
                for (var i = 0; i < records.length; i++) {
                  var added = records[i].addedNodes;
                  for (var j = 0; j < added.length; j++) {
                    if (added[j].nodeType === 1) { pending.push(added[j]); }
                  }
                }
                // A burst larger than this is a page rebuilding itself; the tail is enough.
                if (pending.length > 300) { pending.splice(0, pending.length - 300); }
                if (pending.length) { schedule(); }
              });
              observer.observe(document.documentElement, { childList: true, subtree: true });
              setTimeout(function () {
                try { observer.disconnect(); } catch (e) {}
              }, $OBSERVE_DURATION_MS);
            }
          } catch (e) {}
        })();
        (function () {
          // Hides a site's own fixed bottom nav bar, which duplicates navigation the app
          // already provides and costs a sixth of the screen on a ~480px-tall car display.
          // Found by measuring rather than by a list of known selectors, so it works on any
          // site. Toggled from the drawer.
          //
          // Hiding rather than repositioning is deliberate: an earlier version moved these
          // bars instead, which fought sites that animate the bar's own position (Twitch
          // slides its nav bar on scroll) and left it stranded mid-page. display:none has
          // nothing to fight.
          try {
            if (window.__gearslipFooterHiding) { return; }
            window.__gearslipFooterHiding = true;
            var bridge = window.$BRIDGE_OBJECT_NAME;
            if (!bridge || typeof bridge.hideSiteFootersEnabled !== 'function') { return; }
            if (!bridge.hideSiteFootersEnabled()) { return; }

            var MAX_BAR_HEIGHT = 140;

            function hideBottomBars(root, depth) {
              if (!root || depth < 0 || !root.children) { return; }
              for (var i = 0; i < root.children.length; i++) {
                var el = root.children[i];
                if (el.__gearslipFooterHidden) { continue; }
                var cs;
                try { cs = getComputedStyle(el); } catch (e) { continue; }
                if (cs.display === 'none') { continue; }
                if (cs.position === 'fixed' || cs.position === 'sticky') {
                  var rect;
                  try { rect = el.getBoundingClientRect(); } catch (e) { rect = null; }
                  if (rect && rect.width >= window.innerWidth * 0.6 &&
                      rect.height > 0 && rect.height <= MAX_BAR_HEIGHT &&
                      Math.abs(window.innerHeight - rect.bottom) < 6) {
                    el.__gearslipFooterHidden = true;
                    el.style.setProperty('display', 'none', 'important');
                    continue;
                  }
                }
                hideBottomBars(el, depth - 1);
              }
            }

            function run() {
              try {
                hideBottomBars(document.body, 3);
              } catch (e) {}
            }

            if (document.readyState === 'loading') {
              document.addEventListener('DOMContentLoaded', run);
            } else {
              run();
            }
            // Single-page apps mount their nav bar well after first paint, and swap it out
            // again on navigation, so one pass at load isn't enough.
            var passes = 0;
            var timer = setInterval(function () {
              run();
              if (++passes > 40) { clearInterval(timer); }
            }, 500);
          } catch (e) {}
        })();
        (function () {
          // ":has-text(pattern)" cosmetic rules: hides elements matching a base selector whose
          // own text also matches a pattern. There is no CSS equivalent for the text half, so
          // this runs the check in JavaScript instead of the plain <style> the rest of the
          // cosmetic filters use.
          try {
            if (window.__gearslipHasText) { return; }
            var bridge = window.$BRIDGE_OBJECT_NAME;
            if (!bridge || typeof bridge.hasTextRulesForHost !== 'function') { return; }

            var rules;
            try {
              rules = JSON.parse(bridge.hasTextRulesForHost(location.hostname) || '[]');
            } catch (e) {
              rules = [];
            }
            if (!rules.length) { return; }
            window.__gearslipHasText = true;

            var compiled = [];
            for (var i = 0; i < rules.length; i++) {
              try {
                compiled.push({ selector: rules[i][0], regex: new RegExp(rules[i][1], 'i') });
              } catch (e) {}
            }
            if (!compiled.length) { return; }

            function run() {
              for (var i = 0; i < compiled.length; i++) {
                var rule = compiled[i];
                var elements;
                try { elements = document.querySelectorAll(rule.selector); } catch (e) { continue; }
                for (var j = 0; j < elements.length; j++) {
                  var el = elements[j];
                  if (el.__gearslipHasTextHidden) { continue; }
                  var text = el.textContent;
                  if (text && rule.regex.test(text)) {
                    el.__gearslipHasTextHidden = true;
                    el.style.setProperty('display', 'none', 'important');
                  }
                }
              }
            }

            if (document.readyState === 'loading') {
              document.addEventListener('DOMContentLoaded', run);
            } else {
              run();
            }
            // Matches uBlock Origin's own behaviour for this rule type: it re-evaluates on a
            // timer rather than per-mutation, since a text match can only be judged once an
            // element's subtree has actually finished changing.
            var passes = 0;
            var timer = setInterval(function () {
              run();
              if (++passes > 40) { clearInterval(timer); }
            }, 500);
          } catch (e) {}
        })();
    """.trimIndent()

    fun attach(webView: WebView) {
        webView.addJavascriptInterface(Bridge(webView.context.applicationContext), BRIDGE_OBJECT_NAME)
        if (!needsPageStartFallback) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, INJECTION_JS, setOf("*"))
            }
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    YouTubeAdBlock.INJECTION_JS,
                    setOf("*")
                )
            }
        }
    }

    class Bridge(private val context: Context) {
        @JavascriptInterface
        fun cssForHost(host: String?): String = runCatching {
            AdBlockManager.cssForHost(host)
        }.getOrDefault("")

        @JavascriptInterface
        fun youtubeBlockingEnabled(host: String?): Boolean = runCatching {
            AdBlockManager.isYouTubeBlockingEnabled(host)
        }.getOrDefault(false)

        @JavascriptInterface
        fun customSiteTweaksEnabled(): Boolean = runCatching {
            AdBlockManager.isCustomSiteTweaksEnabled()
        }.getOrDefault(false)

        @JavascriptInterface
        fun hideSiteFootersEnabled(): Boolean = runCatching {
            AdBlockManager.isHideSiteFootersEnabled()
        }.getOrDefault(false)

        @JavascriptInterface
        fun hasTextRulesForHost(host: String?): String = runCatching {
            AdBlockManager.hasTextRulesForHost(host)
        }.getOrDefault("[]")

        @JavascriptInterface
        fun cssForTokens(host: String?, classNames: String?, ids: String?): String = runCatching {
            AdBlockManager.cssForTokens(host, classNames.orEmpty(), ids.orEmpty())
        }.getOrDefault("")
    }
}
