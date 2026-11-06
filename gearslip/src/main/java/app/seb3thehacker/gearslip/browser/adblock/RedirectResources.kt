package app.seb3thehacker.gearslip.browser.adblock

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * A small subset of uBlock Origin's / AdGuard's named `$redirect=` resources.
 *
 * Blocking most tracking scripts outright is safe -- the page never had a live reference to
 * them, so nothing calls into the empty response [AdBlockManager.blockedResponse] already
 * serves. But a handful of scripts are different: page code assumes the object they define
 * (`ga`, `dataLayer`, `googletag`, ...) exists and calls it immediately, so an empty response
 * leaves that call throwing a `ReferenceError` that can break unrelated script on the same page.
 * These stubs define just enough of that surface to no-op safely, which is what the named
 * `$redirect=` resource is for in the filter list that requested it.
 *
 * Anything not in this map still gets blocked -- just with the generic typed-empty response
 * instead of an exact stub, same as before this existed.
 */
internal object RedirectResources {

    private val EMPTY_JS = "".toByteArray()

    private val GOOGLE_ANALYTICS_GA = """
        (function(){function g(){}var t=function(){t.c[t.q.push(arguments)-1]=null};
        t.q=[];t.c=[];window.ga=window.ga||t;window._gaq=window._gaq||{push:g};
        window.GoogleAnalyticsObject=window.GoogleAnalyticsObject||'ga';})();
    """.trimIndent().toByteArray()

    private val GOOGLE_ANALYTICS_ANALYTICS = """
        (function(){function s(){}var g={
        create:function(){return{get:s,set:s,send:s}},getAll:function(){return[]},
        getByName:function(){return null},remove:s};
        window.ga=window.ga||function(){(window.ga.q=window.ga.q||[]).push(arguments)};
        window.ga.create=g.create;window.ga.getAll=g.getAll;window.ga.getByName=g.getByName;
        window.ga.remove=g.remove;window.gaplugins={Linker:function(){this.decorate=s;this.autoLink=s}};
        })();
    """.trimIndent().toByteArray()

    private val GOOGLETAGMANAGER_GTM = """
        (function(){window.dataLayer=window.dataLayer||[];
        window.dataLayer.push=function(){};
        window.google_tag_manager=window.google_tag_manager||{};})();
    """.trimIndent().toByteArray()

    private val GOOGLETAGSERVICES_GPT = """
        (function(){function s(){}function u(){return{addService:function(){return this},
        setTargeting:s,setCollapseEmptyDiv:s,addEventListener:function(){return this},
        removeEventListener:s,get:s,set:s,getSlotElementId:function(){return''},
        getSlotId:function(){return{getDomId:function(){return''}}}}}
        window.googletag=window.googletag||{};
        window.googletag.cmd=window.googletag.cmd||{push:function(fn){try{fn()}catch(e){}}};
        var pubads=u();
        window.googletag.pubads=function(){return pubads};
        window.googletag.defineSlot=function(){return u()};
        window.googletag.defineOutOfPageSlot=function(){return u()};
        window.googletag.enableServices=s;window.googletag.display=s;
        window.googletag.apiReady=true;})();
    """.trimIndent().toByteArray()

    private val NOOP_CSS = "".toByteArray()
    private val NOOP_HTML = "<!doctype html><html><head></head><body></body></html>".toByteArray()
    private val NOOP_JSON = "{}".toByteArray()

    private val STUBS: Map<String, Pair<String, ByteArray>> = buildMap {
        // Names as they appear across uBO's and AdGuard's resources.txt / redirect syntax.
        for (name in listOf("noopjs", "noop.js", "noop-js")) put(name, "application/javascript" to EMPTY_JS)
        for (name in listOf("noopcss", "noop.css", "noop-css")) put(name, "text/css" to NOOP_CSS)
        for (name in listOf("noop.txt", "nooptext")) put(name, "text/plain" to "".toByteArray())
        for (name in listOf("noop.json", "empty")) put(name, "application/json" to NOOP_JSON)
        for (name in listOf("noop.html", "click2load.html")) put(name, "text/html" to NOOP_HTML)
        for (name in listOf("google-analytics_ga.js", "google-analytics.com/ga.js", "ga.js"))
            put(name, "application/javascript" to GOOGLE_ANALYTICS_GA)
        for (name in listOf(
            "google-analytics_analytics.js", "google-analytics.com/analytics.js",
            "googletagmanager_gtag.js", "googletagmanager-gtag"
        )) put(name, "application/javascript" to GOOGLE_ANALYTICS_ANALYTICS)
        for (name in listOf("googletagmanager_gtm.js", "googletagmanager.com/gtm.js", "gtm.js"))
            put(name, "application/javascript" to GOOGLETAGMANAGER_GTM)
        for (name in listOf("googletagservices_gpt.js", "googletagservices.com/gpt.js", "gpt.js"))
            put(name, "application/javascript" to GOOGLETAGSERVICES_GPT)
        for (name in listOf("1x1.gif", "1x1-transparent.gif", "2x2.png", "3x2.png", "32x32.png"))
            put(name, "image/gif" to AdBlockManager.transparentGifBytes)
    }

    /** Null when [name] is not one of the resources we ship a stub for. */
    fun response(name: String, headers: Map<String, String>): WebResourceResponse? {
        val stub = STUBS[name.lowercase()] ?: return null
        val (mimeType, body) = stub
        val encoding = if (mimeType.startsWith("image/")) null else "utf-8"
        return WebResourceResponse(mimeType, encoding, 200, "OK", headers, ByteArrayInputStream(body))
    }
}
