package app.seb3thehacker.gearslip.browser.adblock

/**
 * A parsed network filter rule (Adblock Plus syntax subset).
 *
 * [segments] is the pattern split on `*`; each segment is matched literally except for `^`,
 * which stands for a single separator character (or the end of the URL).
 */
internal class NetworkRule(
    val segments: Array<String>,
    val anchorDomain: Boolean,
    val anchorStart: Boolean,
    val anchorEnd: Boolean,
    val typeMask: Int,
    val excludedTypeMask: Int,
    val thirdParty: Boolean?,
    val includeDomains: Array<String>?,
    val excludeDomains: Array<String>?,
    /** Set when the whole pattern is a bare `||host^`, which lets the engine index it by host. */
    val hostAnchor: String?,
    /** Set for `$redirect=name` / `$redirect-rule=name` rules with a resource we can serve. */
    val redirectResource: String?
) {
    /**
     * True when the rule carries no options, so matching the host is the whole test. The great
     * majority of list entries are of this shape and the engine stores them as bare strings.
     */
    val hasNoOptions: Boolean
        get() = typeMask == 0 && excludedTypeMask == 0 && thirdParty == null &&
            includeDomains == null && excludeDomains == null && redirectResource == null
}

internal class CosmeticRule(
    val selector: String,
    val includeDomains: List<String>?,
    val excludeDomains: List<String>?,
    val isException: Boolean,
    /** Set for a `selector:has-text(pattern)` rule: the text [pattern] to match within
     *  [selector]'s matches, applied by JavaScript rather than as plain CSS. */
    val hasTextPattern: String? = null
)

internal object FilterParser {

    /**
     * Options that change *how* a request is answered rather than whether it is blocked.
     * A rule carrying one of these is dropped: honouring only its matching half would block
     * requests the list author meant to rewrite or merely restrict.
     */
    private val UNSUPPORTED_OPTIONS = hashSetOf(
        "csp", "removeparam", "rewrite", "replace", "empty", "mp4",
        "inline-script", "inline-font", "genericblock", "generichide", "specifichide", "elemhide",
        "badfilter", "denyallow", "method", "header", "to", "from", "ipaddress", "permissions",
        "uritransform", "urlskip", "urltransform", "strict1p", "strict3p", "popup", "popunder",
        "cname", "webrtc", "stealth", "cookie", "network", "app", "extension", "jsonprune",
        "hls", "referrerpolicy", "match-case-path"
    )

    /**
     * Procedural cosmetic syntax that needs uBlock Origin's own runtime to evaluate. These
     * selectors cannot be expressed as plain CSS, so the rules are skipped.
     *
     * `:has()` is not in this list: modern WebView's Chromium supports it natively as plain CSS,
     * so those selectors are just passed straight through. `:has-text()` is handled separately in
     * [parseCosmeticRule] rather than being skipped outright -- the simple, common form (a plain
     * selector followed by one `:has-text(...)`) is split out and matched by JavaScript instead of
     * CSS, since there is no CSS equivalent for a text match.
     */
    private val PROCEDURAL_MARKERS = arrayOf(
        // ":has-text(" stays here as a safety net: it's stripped out before this check runs
        // when it's the simple trailing form [extractHasText] understands, so a leftover
        // occurrence here means that extraction either wasn't attempted (not a cosmetic rule
        // that reached it) or failed (nested/combined usage), and the rule should be dropped
        // rather than shipped as literal, invalid CSS.
        ":has-text(",
        ":matches-css", ":matches-media", ":matches-path", ":matches-attr",
        ":xpath(", ":upward(", ":nth-ancestor(", ":style(", ":remove(", ":remove-attr(",
        ":remove-class(", ":watch-attr(", ":min-text-length(", ":others(", ":shadow(", ":if(",
        ":if-not(", ":not(:has"
    )

    private fun isHostCharacter(c: Char): Boolean =
        c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'

    fun parseNetworkRule(line: String): NetworkRule? {
        var body = line
        if (body.startsWith("@@")) body = body.substring(2)
        if (body.isEmpty()) return null

        var pattern = body
        var optionText: String? = null
        val dollar = body.lastIndexOf('$')
        if (dollar >= 0) {
            pattern = body.substring(0, dollar)
            optionText = body.substring(dollar + 1)
        }

        // Regular-expression rules (/pattern/) are not supported.
        if (pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")) return null
        if (pattern.isEmpty()) return null

        var anchorDomain = false
        var anchorStart = false
        var anchorEnd = false
        if (pattern.startsWith("||")) {
            anchorDomain = true
            pattern = pattern.substring(2)
        } else if (pattern.startsWith("|")) {
            anchorStart = true
            pattern = pattern.substring(1)
        }
        if (pattern.endsWith("|")) {
            anchorEnd = true
            pattern = pattern.dropLast(1)
        }
        if (pattern.isEmpty()) return null
        pattern = pattern.lowercase()

        var typeMask = 0
        var excludedTypeMask = 0
        var thirdParty: Boolean? = null
        var includeDomains: MutableList<String>? = null
        var excludeDomains: MutableList<String>? = null
        var redirectResource: String? = null

        if (optionText != null) {
            for (rawOption in optionText.split(',')) {
                var option = rawOption.trim().lowercase()
                if (option.isEmpty()) continue

                var negated = false
                if (option.startsWith("~")) {
                    negated = true
                    option = option.substring(1)
                }

                val equals = option.indexOf('=')
                val key = if (equals >= 0) option.substring(0, equals) else option
                val value = if (equals >= 0) option.substring(equals + 1) else null

                if (key.isEmpty()) return null
                if (key in UNSUPPORTED_OPTIONS) return null

                when (key) {
                    "domain" -> {
                        if (value.isNullOrEmpty()) return null
                        for (entry in value.split('|')) {
                            val domain = entry.trim()
                            if (domain.isEmpty()) continue
                            if (domain.startsWith("~")) {
                                val excluded = domain.substring(1)
                                if (excluded.isNotEmpty()) {
                                    (excludeDomains ?: mutableListOf<String>()
                                        .also { excludeDomains = it }).add(excluded)
                                }
                            } else {
                                (includeDomains ?: mutableListOf<String>()
                                    .also { includeDomains = it }).add(domain)
                            }
                        }
                    }
                    "third-party", "3p" -> thirdParty = !negated
                    "first-party", "1p" -> thirdParty = negated
                    // Patterns are lower-cased for matching, so $match-case degrades to
                    // case-insensitive. It only ever widens the match.
                    "match-case", "important" -> Unit
                    "all" -> Unit
                    // `redirect-rule` only fires when another rule would already block the
                    // request; treating it the same as `redirect` is a harmless simplification
                    // since we only reach here once a block decision is otherwise made.
                    "redirect", "redirect-rule" -> {
                        // AdGuard's priority suffix (`name:10`) doesn't apply to a single-engine
                        // blocker with no other redirect rule to rank against.
                        val name = value?.substringBefore(':')
                        if (name.isNullOrEmpty()) return null
                        redirectResource = name
                    }
                    else -> {
                        val bit = ResourceType.fromOptionName(key)
                        if (bit == ResourceType.NONE) return null
                        if (negated) excludedTypeMask = excludedTypeMask or bit
                        else typeMask = typeMask or bit
                    }
                }
            }
        }

        val segments = splitOnWildcards(pattern)
        if (segments.isEmpty()) return null

        var hostAnchor: String? = null
        if (anchorDomain && !anchorEnd && segments.size == 1) {
            val candidate = if (pattern.endsWith("^")) pattern.dropLast(1) else pattern
            if (candidate.isNotEmpty() && candidate.all { isHostCharacter(it) }) {
                hostAnchor = candidate
            }
        }

        return NetworkRule(
            segments = segments,
            anchorDomain = anchorDomain,
            anchorStart = anchorStart,
            anchorEnd = anchorEnd,
            typeMask = typeMask,
            excludedTypeMask = excludedTypeMask,
            thirdParty = thirdParty,
            includeDomains = includeDomains?.toTypedArray(),
            excludeDomains = excludeDomains?.toTypedArray(),
            hostAnchor = hostAnchor,
            redirectResource = redirectResource
        )
    }

    private fun splitOnWildcards(pattern: String): Array<String> {
        if (pattern.indexOf('*') < 0) return arrayOf(pattern)
        return pattern.split('*').toTypedArray()
    }

    /** Returns the index of the cosmetic separator, or -1 when the line is not a cosmetic rule. */
    fun cosmeticSeparatorIndex(line: String): Int {
        var search = 0
        while (true) {
            val hash = line.indexOf('#', search)
            if (hash < 0) return -1
            if (hash + 1 < line.length) {
                val next = line[hash + 1]
                if (next == '#') return hash
                if (next == '@' && hash + 2 < line.length && line[hash + 2] == '#') return hash
                // #?#, #$#, #$?#, #%# and friends are procedural or scriptlet syntax.
                if (next == '?' || next == '$' || next == '%') return hash
            }
            search = hash + 1
        }
    }

    fun parseCosmeticRule(line: String, separatorIndex: Int): CosmeticRule? {
        val isException: Boolean
        val selectorStart: Int
        when {
            line.startsWith("#@#", separatorIndex) -> {
                isException = true
                selectorStart = separatorIndex + 3
            }
            line.startsWith("##", separatorIndex) -> {
                isException = false
                selectorStart = separatorIndex + 2
            }
            // Procedural / scriptlet separators cannot be represented as CSS.
            else -> return null
        }

        if (selectorStart >= line.length) return null
        var selector = line.substring(selectorStart).trim()
        if (selector.isEmpty()) return null
        if (selector.startsWith("+js(") || selector.startsWith("script:")) return null

        var hasTextPattern: String? = null
        val hasTextSplit = extractHasText(selector)
        if (hasTextSplit != null) {
            selector = hasTextSplit.first
            hasTextPattern = hasTextSplit.second
        }

        val lowerSelector = selector.lowercase()
        if (PROCEDURAL_MARKERS.any { lowerSelector.contains(it) }) return null
        // A stray brace would let a selector escape its own rule when concatenated.
        if (selector.contains('{') || selector.contains('}')) return null

        var includeDomains: MutableList<String>? = null
        var excludeDomains: MutableList<String>? = null
        val domainText = line.substring(0, separatorIndex).trim()
        if (domainText.isNotEmpty()) {
            for (entry in domainText.split(',')) {
                val domain = entry.trim().lowercase()
                if (domain.isEmpty()) continue
                if (domain.startsWith("~")) {
                    val excluded = domain.substring(1)
                    if (excluded.isNotEmpty()) {
                        (excludeDomains ?: mutableListOf<String>().also { excludeDomains = it })
                            .add(excluded)
                    }
                } else {
                    (includeDomains ?: mutableListOf<String>().also { includeDomains = it })
                        .add(domain)
                }
            }
        }

        return CosmeticRule(selector, includeDomains, excludeDomains, isException, hasTextPattern)
    }

    /**
     * Splits `selector:has-text(pattern)` into (`selector`, JS regex source). Only the simple,
     * common form is handled -- one `:has-text(...)` as the very last thing in the selector, with
     * a non-empty selector before it. Anything else (nested inside `:has()`, combined with other
     * procedural syntax, an empty base selector matching every element) returns null and the
     * caller falls back to discarding the rule, same as before this existed.
     */
    private fun extractHasText(selector: String): Pair<String, String>? {
        val markerIndex = selector.indexOf(":has-text(")
        if (markerIndex <= 0) return null

        val argStart = markerIndex + ":has-text(".length
        var depth = 1
        var index = argStart
        while (index < selector.length && depth > 0) {
            when (selector[index]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth > 0) index++
        }
        // The closing paren must be the last character: a trailing combinator or pseudo after
        // it means this is a more complex selector than the simple form handled here.
        if (depth != 0 || index != selector.length - 1) return null

        val base = selector.substring(0, markerIndex).trim()
        if (base.isEmpty() || base.contains(":has-text(")) return null

        var arg = selector.substring(argStart, index).trim()
        if (arg.isEmpty()) return null

        val pattern = if (arg.length > 1 && arg.startsWith("/") && arg.lastIndexOf('/') > 0) {
            arg.substring(1, arg.lastIndexOf('/'))
        } else {
            Regex.escape(arg)
        }
        return base to pattern
    }
}
