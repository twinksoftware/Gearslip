package app.seb3thehacker.gearslip.browser.adblock

import android.webkit.WebResourceRequest

/**
 * Resource types used by filter list options ($script, $image, ...).
 *
 * WebResourceRequest does not expose the resource type directly, so [infer] reconstructs it
 * from the Sec-Fetch-Dest request header where WebView sends one, falling back to the file
 * extension and then the Accept header.
 */
internal object ResourceType {
    const val NONE = 0
    const val DOCUMENT = 1 shl 0
    const val SUBDOCUMENT = 1 shl 1
    const val SCRIPT = 1 shl 2
    const val IMAGE = 1 shl 3
    const val STYLESHEET = 1 shl 4
    const val XHR = 1 shl 5
    const val FONT = 1 shl 6
    const val MEDIA = 1 shl 7
    const val WEBSOCKET = 1 shl 8
    const val PING = 1 shl 9
    const val OBJECT = 1 shl 10
    const val OTHER = 1 shl 11

    fun fromOptionName(name: String): Int = when (name) {
        "document" -> DOCUMENT
        "subdocument", "frame" -> SUBDOCUMENT
        "script" -> SCRIPT
        "image" -> IMAGE
        "stylesheet", "css" -> STYLESHEET
        "xmlhttprequest", "xhr" -> XHR
        "font" -> FONT
        "media" -> MEDIA
        "websocket" -> WEBSOCKET
        "ping", "beacon" -> PING
        "object", "object-subrequest" -> OBJECT
        "other" -> OTHER
        else -> NONE
    }

    fun infer(request: WebResourceRequest): Int {
        if (request.isForMainFrame) return DOCUMENT

        val headers = request.requestHeaders
        val dest = headers?.entries
            ?.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
            ?.value
            ?.lowercase()

        if (!dest.isNullOrEmpty() && dest != "empty") {
            val fromDest = when (dest) {
                "document" -> DOCUMENT
                "iframe", "frame", "embed" -> SUBDOCUMENT
                "script", "worker", "sharedworker", "serviceworker" -> SCRIPT
                "image" -> IMAGE
                "style" -> STYLESHEET
                "font" -> FONT
                "audio", "video", "track", "audioworklet" -> MEDIA
                "object" -> OBJECT
                "report" -> PING
                "manifest" -> OTHER
                else -> NONE
            }
            if (fromDest != NONE) return fromDest
        }

        val fromExtension = extensionType(request.url?.path)
        if (fromExtension != NONE) return fromExtension

        val accept = headers?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value
            ?.lowercase()
        if (accept != null) {
            when {
                accept.startsWith("image/") || accept.contains("image/avif") -> return IMAGE
                accept.startsWith("text/css") -> return STYLESHEET
                accept.startsWith("video/") || accept.startsWith("audio/") -> return MEDIA
                accept.contains("application/javascript") -> return SCRIPT
            }
        }

        // "empty" is what fetch()/XMLHttpRequest report, and is also the fallback when
        // WebView sends no Sec-Fetch-Dest at all.
        return if (dest == "empty") XHR else OTHER
    }

    private fun extensionType(path: String?): Int {
        if (path.isNullOrEmpty()) return NONE
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return NONE
        return when (path.substring(dot + 1).lowercase()) {
            "js", "mjs" -> SCRIPT
            "css" -> STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif" -> IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> FONT
            "mp4", "webm", "ogg", "mp3", "m4a", "m4s", "ts", "m3u8", "mpd" -> MEDIA
            "json" -> XHR
            "swf" -> OBJECT
            else -> NONE
        }
    }
}

/** Helpers for comparing hosts without pulling in a full public suffix list. */
internal object DomainUtils {
    /**
     * Common multi-label public suffixes. This is deliberately a short list rather than the
     * full PSL: it is only used to decide first-party vs third-party, where being wrong means
     * a $third-party rule misfires on an uncommon TLD, not a broken page.
     */
    private val MULTI_LABEL_SUFFIXES = hashSetOf(
        "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk", "net.uk", "sch.uk",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.nz", "net.nz", "org.nz",
        "com.br", "net.br", "org.br",
        "co.in", "net.in", "org.in",
        "com.cn", "net.cn", "org.cn",
        "co.kr", "or.kr",
        "com.mx", "com.ar", "com.tr", "com.tw", "com.hk", "com.sg", "com.my",
        "co.za", "com.pl", "com.ua", "co.il", "com.vn", "com.ph", "co.id",
        "com.es", "com.pt", "com.gr", "com.ro", "com.ru", "org.ru", "net.ru"
    )

    fun registrableDomain(host: String?): String? {
        if (host.isNullOrEmpty()) return null
        val normalized = host.trimEnd('.').lowercase()
        if (normalized.isEmpty()) return null
        if (normalized.indexOf(':') >= 0) return normalized

        val labels = normalized.split('.')
        if (labels.size <= 2) return normalized
        if (labels.all { label -> label.isNotEmpty() && label.all { it in '0'..'9' } }) return normalized

        val lastTwo = labels[labels.size - 2] + "." + labels[labels.size - 1]
        if (lastTwo in MULTI_LABEL_SUFFIXES && labels.size >= 3) {
            return labels[labels.size - 3] + "." + lastTwo
        }
        return lastTwo
    }

    fun isThirdParty(requestHost: String?, documentHost: String?): Boolean {
        if (requestHost.isNullOrEmpty() || documentHost.isNullOrEmpty()) return false
        val request = registrableDomain(requestHost) ?: return false
        val document = registrableDomain(documentHost) ?: return false
        return !request.equals(document, ignoreCase = true)
    }

    /** True when [host] is [domain] itself or a subdomain of it. */
    fun hostMatchesDomain(host: String, domain: String): Boolean {
        if (host == domain) return true
        return host.length > domain.length &&
            host.endsWith(domain) &&
            host[host.length - domain.length - 1] == '.'
    }

    /** Emits `a.b.example.com`, `b.example.com`, `example.com`, `com`. */
    inline fun forEachHostSuffix(host: String, action: (String) -> Unit) {
        var index = 0
        while (true) {
            action(if (index == 0) host else host.substring(index))
            val dot = host.indexOf('.', index)
            if (dot < 0) return
            index = dot + 1
        }
    }
}
