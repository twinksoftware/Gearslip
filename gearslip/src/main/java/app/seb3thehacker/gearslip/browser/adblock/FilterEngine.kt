package app.seb3thehacker.gearslip.browser.adblock

import android.util.LruCache

/**
 * An immutable, queryable index over a set of parsed filter rules.
 *
 * Network rules are bucketed so that a request only ever tests a handful of them:
 *  - `||host^` rules are indexed by host and found by walking the request host's suffixes;
 *  - every other rule is indexed under one literal token from its pattern, and a request only
 *    tests rules whose token appears in the URL;
 *  - the few rules with no usable token are tested on every request.
 *
 * Element-hiding rules are split the same way. Nearly all generic hiding rules are anchored on
 * a single class or id (`.ad-banner`, `#sponsored`), so they are indexed by that name and only
 * handed to a page once that class or id actually appears in its DOM. Injecting all of them
 * unconditionally would mean roughly 400 KB of CSS on every page load.
 */
internal class FilterEngine private constructor(
    private val blockRules: RuleSet,
    private val allowRules: RuleSet,
    private val genericComplexSelectors: List<String>,
    private val genericByClass: HashMap<String, MutableList<String>>,
    private val genericById: HashMap<String, MutableList<String>>,
    private val specificSelectors: HashMap<String, MutableList<String>>,
    private val cosmeticExceptions: HashMap<String, MutableList<String>>,
    private val genericHasText: List<Pair<String, String>>,
    private val specificHasText: HashMap<String, MutableList<Pair<String, String>>>,
    val networkRuleCount: Int,
    val cosmeticRuleCount: Int
) {

    private val hostCssCache = LruCache<String, String>(32)

    /** True when the request should be blocked at all, regardless of which rule matched. */
    fun shouldBlock(
        url: String,
        requestHost: String?,
        documentHost: String?,
        type: Int,
        isThirdParty: Boolean
    ): Boolean = matchBlockingRule(url, requestHost, documentHost, type, isThirdParty) != null

    /**
     * The block match for this request, or null when it is not blocked. Split out from
     * [shouldBlock] so the caller can also read the matched rule's `$redirect=` resource name
     * without a second, separate walk over the same rule sets. A plain `||host^` match (the
     * common case) carries no [NetworkRule] object at all, hence [BlockMatch] rather than
     * returning the rule directly.
     */
    fun matchBlockingRule(
        url: String,
        requestHost: String?,
        documentHost: String?,
        type: Int,
        isThirdParty: Boolean
    ): BlockMatch? {
        val lowerUrl = url.lowercase()
        val hostStart = hostStart(lowerUrl)
        val hostEnd = hostEnd(lowerUrl, hostStart)
        val tokens = ArrayList<String>(16)
        tokenize(lowerUrl, tokens)

        val blockingMatch = matchingRule(blockRules, lowerUrl, hostStart, hostEnd, requestHost, documentHost, type, isThirdParty, tokens)
            ?: return null
        if (matchesAny(allowRules, lowerUrl, hostStart, hostEnd, requestHost, documentHost, type, isThirdParty, tokens)) {
            return null
        }
        return blockingMatch
    }

    class BlockMatch(val redirectResource: String?) {
        companion object {
            val PLAIN = BlockMatch(null)
        }
    }

    /**
     * The stylesheet injected at document start: rules written for this site, plus the handful
     * of generic rules that are not anchored on a class or id.
     */
    fun cssForHost(host: String?): String {
        val normalized = host?.lowercase().orEmpty()
        hostCssCache.get(normalized)?.let { return it }

        val exceptions = exceptionsFor(normalized)
        val selectors = ArrayList<String>(genericComplexSelectors.size + 32)
        if (normalized.isNotEmpty()) {
            DomainUtils.forEachHostSuffix(normalized) { suffix ->
                specificSelectors[suffix]?.let { selectors.addAll(it) }
            }
        }
        selectors.addAll(genericComplexSelectors)

        val css = buildHidingCss(selectors, exceptions)
        hostCssCache.put(normalized, css)
        return css
    }

    private val hasTextCache = LruCache<String, List<Pair<String, String>>>(32)

    /**
     * The `:has-text()` rules (base selector, JS regex source) that apply to [host]. Delivered
     * separately from [cssForHost] because there is no CSS equivalent for a text match -- the
     * caller runs these with JavaScript instead of a `<style>` element.
     */
    fun hasTextRulesForHost(host: String?): List<Pair<String, String>> {
        val normalized = host?.lowercase().orEmpty()
        hasTextCache.get(normalized)?.let { return it }

        val rules = ArrayList<Pair<String, String>>(genericHasText.size + 4)
        if (normalized.isNotEmpty()) {
            DomainUtils.forEachHostSuffix(normalized) { suffix ->
                specificHasText[suffix]?.let { rules.addAll(it) }
            }
        }
        rules.addAll(genericHasText)

        hasTextCache.put(normalized, rules)
        return rules
    }

    /**
     * The stylesheet for generic rules whose class or id has turned up in the page. [classNames]
     * and [ids] are whitespace-separated, as collected from the live DOM.
     */
    fun cssForTokens(host: String?, classNames: String, ids: String): String {
        val normalized = host?.lowercase().orEmpty()
        val exceptions = exceptionsFor(normalized)
        val selectors = ArrayList<String>()

        collectByToken(classNames, genericByClass, selectors)
        collectByToken(ids, genericById, selectors)
        if (selectors.isEmpty()) return ""

        return buildHidingCss(selectors, exceptions)
    }

    private fun collectByToken(
        tokens: String,
        index: HashMap<String, MutableList<String>>,
        out: MutableList<String>
    ) {
        if (tokens.isEmpty() || index.isEmpty()) return
        var start = 0
        val length = tokens.length
        while (start < length) {
            while (start < length && tokens[start].isWhitespace()) start++
            if (start >= length) break
            var end = start
            while (end < length && !tokens[end].isWhitespace()) end++
            index[tokens.substring(start, end)]?.let { out.addAll(it) }
            start = end
        }
    }

    private fun exceptionsFor(host: String): Set<String> {
        if (host.isEmpty() || cosmeticExceptions.isEmpty()) return emptySet()
        var result: MutableSet<String>? = null
        DomainUtils.forEachHostSuffix(host) { suffix ->
            cosmeticExceptions[suffix]?.let { exceptions ->
                val target = result ?: HashSet<String>().also { result = it }
                target.addAll(exceptions)
            }
        }
        return result ?: emptySet()
    }

    private fun buildHidingCss(selectors: List<String>, exceptions: Set<String>): String {
        val usable = if (exceptions.isEmpty()) {
            selectors
        } else {
            selectors.filterNot { it in exceptions }
        }
        if (usable.isEmpty()) return ""

        val builder = StringBuilder(usable.size * 24)
        // One rule per chunk rather than one rule for everything: a browser drops an entire
        // rule if any selector in it fails to parse, so a chunk limits that blast radius.
        for (chunk in usable.chunked(SELECTORS_PER_RULE)) {
            builder.append(chunk.joinToString(","))
            builder.append("{display:none!important}")
        }
        return builder.toString()
    }

    private fun matchesAny(
        ruleSet: RuleSet,
        url: String,
        hostStart: Int,
        hostEnd: Int,
        requestHost: String?,
        documentHost: String?,
        type: Int,
        isThirdParty: Boolean,
        tokens: List<String>
    ): Boolean {
        if (!requestHost.isNullOrEmpty()) {
            var found = false
            DomainUtils.forEachHostSuffix(requestHost) { suffix ->
                if (!found) {
                    if (suffix in ruleSet.plainHosts) {
                        found = true
                    } else {
                        val candidates = ruleSet.hostIndex[suffix]
                        if (candidates != null) {
                            for (rule in candidates) {
                                if (optionsMatch(rule, type, isThirdParty, documentHost)) {
                                    found = true
                                    break
                                }
                            }
                        }
                    }
                }
            }
            if (found) return true
        }

        for (token in tokens) {
            val candidates = ruleSet.tokenIndex[token] ?: continue
            for (rule in candidates) {
                if (optionsMatch(rule, type, isThirdParty, documentHost) &&
                    matchesUrl(rule, url, hostStart, hostEnd)
                ) {
                    return true
                }
            }
        }

        for (rule in ruleSet.catchAll) {
            if (optionsMatch(rule, type, isThirdParty, documentHost) &&
                matchesUrl(rule, url, hostStart, hostEnd)
            ) {
                return true
            }
        }
        return false
    }

    /** Same walk as [matchesAny], but returns the match itself instead of a plain boolean. */
    private fun matchingRule(
        ruleSet: RuleSet,
        url: String,
        hostStart: Int,
        hostEnd: Int,
        requestHost: String?,
        documentHost: String?,
        type: Int,
        isThirdParty: Boolean,
        tokens: List<String>
    ): BlockMatch? {
        if (!requestHost.isNullOrEmpty()) {
            var result: BlockMatch? = null
            DomainUtils.forEachHostSuffix(requestHost) { suffix ->
                if (result == null) {
                    if (suffix in ruleSet.plainHosts) {
                        result = BlockMatch.PLAIN
                    } else {
                        val candidates = ruleSet.hostIndex[suffix]
                        if (candidates != null) {
                            for (rule in candidates) {
                                if (optionsMatch(rule, type, isThirdParty, documentHost)) {
                                    result = BlockMatch(rule.redirectResource)
                                    break
                                }
                            }
                        }
                    }
                }
            }
            if (result != null) return result
        }

        for (token in tokens) {
            val candidates = ruleSet.tokenIndex[token] ?: continue
            for (rule in candidates) {
                if (optionsMatch(rule, type, isThirdParty, documentHost) &&
                    matchesUrl(rule, url, hostStart, hostEnd)
                ) {
                    return BlockMatch(rule.redirectResource)
                }
            }
        }

        for (rule in ruleSet.catchAll) {
            if (optionsMatch(rule, type, isThirdParty, documentHost) &&
                matchesUrl(rule, url, hostStart, hostEnd)
            ) {
                return BlockMatch(rule.redirectResource)
            }
        }
        return null
    }

    private fun optionsMatch(
        rule: NetworkRule,
        type: Int,
        isThirdParty: Boolean,
        documentHost: String?
    ): Boolean {
        if (rule.typeMask != 0 && (rule.typeMask and type) == 0) return false
        if (rule.excludedTypeMask != 0 && (rule.excludedTypeMask and type) != 0) return false

        val thirdParty = rule.thirdParty
        if (thirdParty != null && thirdParty != isThirdParty) return false

        val include = rule.includeDomains
        val exclude = rule.excludeDomains
        if (include != null || exclude != null) {
            val host = documentHost ?: return false
            if (exclude != null && exclude.any { DomainUtils.hostMatchesDomain(host, it) }) return false
            if (include != null && !include.any { DomainUtils.hostMatchesDomain(host, it) }) return false
        }
        return true
    }

    private fun matchesUrl(rule: NetworkRule, url: String, hostStart: Int, hostEnd: Int): Boolean {
        if (rule.anchorDomain) {
            if (matchFrom(url, hostStart, rule)) return true
            var index = hostStart
            while (index < hostEnd) {
                if (url[index] == '.' && matchFrom(url, index + 1, rule)) return true
                index++
            }
            return false
        }
        if (rule.anchorStart) return matchFrom(url, 0, rule)

        val first = rule.segments[0]
        if (first.isNotEmpty() && first.indexOf('^') < 0) {
            var index = url.indexOf(first)
            while (index >= 0) {
                if (matchFrom(url, index, rule)) return true
                index = url.indexOf(first, index + 1)
            }
            return false
        }

        var index = 0
        while (index <= url.length) {
            if (matchFrom(url, index, rule)) return true
            index++
        }
        return false
    }

    private fun matchFrom(url: String, start: Int, rule: NetworkRule): Boolean {
        val segments = rule.segments
        var position = matchSegmentAt(url, start, segments[0])
        if (position < 0) return false

        for (i in 1 until segments.size) {
            val segment = segments[i]
            if (segment.isEmpty()) continue
            var probe = position
            var matchedEnd = -1
            while (probe <= url.length) {
                val end = matchSegmentAt(url, probe, segment)
                if (end >= 0) {
                    matchedEnd = end
                    break
                }
                probe++
            }
            if (matchedEnd < 0) return false
            position = matchedEnd
        }

        if (rule.anchorEnd && segments[segments.size - 1].isNotEmpty() && position != url.length) {
            return false
        }
        return true
    }

    /** Returns the index just past the match, or -1. `^` matches one separator or end-of-URL. */
    private fun matchSegmentAt(url: String, start: Int, segment: String): Int {
        var position = start
        for (i in segment.indices) {
            val expected = segment[i]
            if (expected == '^') {
                if (position >= url.length) {
                    // A separator may stand for the end of the address, but only as the last
                    // character of the segment.
                    return if (i == segment.length - 1) position else -1
                }
                if (!isSeparator(url[position])) return -1
                position++
            } else {
                if (position >= url.length || url[position] != expected) return -1
                position++
            }
        }
        return position
    }

    companion object {
        private const val MIN_TOKEN_LENGTH = 3
        private const val SELECTORS_PER_RULE = 100

        private fun isSeparator(c: Char): Boolean = when {
            c in 'a'..'z' -> false
            c in 'A'..'Z' -> false
            c in '0'..'9' -> false
            c == '_' || c == '-' || c == '.' || c == '%' -> false
            else -> true
        }

        private fun hostStart(url: String): Int {
            val scheme = url.indexOf("://")
            return if (scheme < 0) 0 else scheme + 3
        }

        private fun hostEnd(url: String, hostStart: Int): Int {
            var index = hostStart
            while (index < url.length) {
                val c = url[index]
                if (c == '/' || c == '?' || c == '#' || c == ':') return index
                index++
            }
            return url.length
        }

        private fun tokenize(text: String, out: MutableList<String>) {
            var index = 0
            val length = text.length
            while (index < length) {
                val c = text[index]
                if (c.isLetterOrDigit()) {
                    var end = index + 1
                    while (end < length && text[end].isLetterOrDigit()) end++
                    if (end - index >= MIN_TOKEN_LENGTH) out.add(text.substring(index, end))
                    index = end
                } else {
                    index++
                }
            }
        }

        /** The longest literal token in the pattern, used as the rule's index key. */
        private fun bestToken(rule: NetworkRule): String? {
            var best: String? = null
            val tokens = ArrayList<String>(4)
            for (segment in rule.segments) {
                tokens.clear()
                tokenize(segment, tokens)
                for (token in tokens) {
                    val current = best
                    if (current == null || token.length > current.length) best = token
                }
            }
            return best
        }

        /**
         * The leading class or id a selector is anchored on, as (`.` or `#`) to name, or null
         * when the selector does not start with a plain class or id.
         */
        private fun anchorToken(selector: String): Pair<Char, String>? {
            if (selector.length < 2) return null
            val kind = selector[0]
            if (kind != '.' && kind != '#') return null
            var end = 1
            while (end < selector.length) {
                val c = selector[end]
                if (c.isLetterOrDigit() || c == '-' || c == '_') end++ else break
            }
            if (end == 1) return null
            return kind to selector.substring(1, end)
        }

        fun build(lines: Sequence<String>): FilterEngine {
            val builder = Builder()
            for (line in lines) builder.addLine(line)
            return builder.finish()
        }
    }

    /**
     * Accumulates rules one line at a time, fed straight from a reader so that the whole list
     * never exists in memory as a collection of lines. Parsing EasyList and EasyPrivacy
     * together takes roughly 250ms on a Pixel 6 in a release build. Be wary of profiling this
     * in a debug build: `android:debuggable` disables ART's inlining and made the same work
     * take over nine seconds, which looks like an algorithmic problem but is not one.
     */
    internal class Builder {
        private val blockRules = RuleSet()
        private val allowRules = RuleSet()
        private val genericComplex = LinkedHashSet<String>()
        // Pre-sized: EasyList and EasyPrivacy together fill these to roughly these counts, and
        // rehashing a 90k-entry map on the way there is pure waste.
        private val genericByClass = HashMap<String, MutableList<String>>(16384)
        private val genericById = HashMap<String, MutableList<String>>(8192)
        private val specificSelectors = HashMap<String, MutableList<String>>(16384)
        private val cosmeticExceptions = HashMap<String, MutableList<String>>(1024)
        private val genericHasText = mutableListOf<Pair<String, String>>()
        private val specificHasText = HashMap<String, MutableList<Pair<String, String>>>()
        private var networkRuleCount = 0
        private var cosmeticRuleCount = 0

        fun addLine(rawLine: String) {
            if (rawLine.isEmpty()) return
            // Only pay for trim() on the rare line that actually has surrounding whitespace.
            val needsTrim = rawLine[0] == ' ' || rawLine[0] == '\t' ||
                rawLine[rawLine.length - 1] == ' ' || rawLine[rawLine.length - 1] == '\t'
            val line = if (needsTrim) rawLine.trim() else rawLine
            if (line.isEmpty()) return
            if (line[0] == '!' || line[0] == '[') return

            val separator = FilterParser.cosmeticSeparatorIndex(line)
            if (separator >= 0) {
                addCosmeticRule(line, separator)
                return
            }

            val isException = line.startsWith("@@")
            val rule = FilterParser.parseNetworkRule(line) ?: return
            networkRuleCount++
            val target = if (isException) allowRules else blockRules

            val hostAnchor = rule.hostAnchor
            if (hostAnchor != null) {
                if (rule.hasNoOptions) {
                    target.plainHosts.add(hostAnchor)
                } else {
                    target.hostIndex.getOrPut(hostAnchor) { mutableListOf() }.add(rule)
                }
                return
            }

            val token = bestToken(rule)
            if (token != null) {
                target.tokenIndex.getOrPut(token) { mutableListOf() }.add(rule)
            } else {
                target.catchAll.add(rule)
            }
        }

        private fun addCosmeticRule(line: String, separator: Int) {
            val rule = FilterParser.parseCosmeticRule(line, separator) ?: return
            cosmeticRuleCount++

            // :has-text() has no CSS equivalent, so it is matched by JavaScript from its own
            // list instead of joining the CSS selector strings above. Exceptions for it are not
            // supported -- rare enough in EasyList/EasyPrivacy that dropping them outright (by
            // not indexing this rule) is a safe simplification rather than a real gap.
            val textPattern = rule.hasTextPattern
            if (textPattern != null) {
                if (rule.isException) return
                val entry = rule.selector to textPattern
                if (rule.includeDomains != null) {
                    rule.includeDomains.forEach { domain ->
                        specificHasText.getOrPut(domain) { mutableListOf() }.add(entry)
                    }
                } else {
                    genericHasText.add(entry)
                }
                return
            }

            when {
                rule.isException -> {
                    rule.includeDomains?.forEach { domain ->
                        cosmeticExceptions.getOrPut(domain) { mutableListOf() }.add(rule.selector)
                    }
                }
                rule.includeDomains != null -> {
                    rule.includeDomains.forEach { domain ->
                        specificSelectors.getOrPut(domain) { mutableListOf() }.add(rule.selector)
                    }
                }
                else -> {
                    val anchor = anchorToken(rule.selector)
                    if (anchor == null) {
                        genericComplex.add(rule.selector)
                    } else {
                        val index = if (anchor.first == '.') genericByClass else genericById
                        index.getOrPut(anchor.second) { mutableListOf() }.add(rule.selector)
                    }
                    // `~example.com##.ad` hides everywhere except the excluded domains.
                    rule.excludeDomains?.forEach { domain ->
                        cosmeticExceptions.getOrPut(domain) { mutableListOf() }.add(rule.selector)
                    }
                }
            }
        }

        fun finish(): FilterEngine = FilterEngine(
            blockRules = blockRules,
            allowRules = allowRules,
            genericComplexSelectors = genericComplex.toList(),
            genericByClass = genericByClass,
            genericById = genericById,
            specificSelectors = specificSelectors,
            cosmeticExceptions = cosmeticExceptions,
            genericHasText = genericHasText,
            specificHasText = specificHasText,
            networkRuleCount = networkRuleCount,
            cosmeticRuleCount = cosmeticRuleCount
        )
    }

    internal class RuleSet {
        /** Bare `||host^` blocks with no options: matching the host is the whole test. */
        val plainHosts = HashSet<String>()
        val hostIndex = HashMap<String, MutableList<NetworkRule>>()
        val tokenIndex = HashMap<String, MutableList<NetworkRule>>()
        val catchAll = ArrayList<NetworkRule>()
    }
}
