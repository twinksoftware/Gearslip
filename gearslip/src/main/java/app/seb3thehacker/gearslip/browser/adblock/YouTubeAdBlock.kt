package app.seb3thehacker.gearslip.browser.adblock

/**
 * YouTube ad blocking, plus the cosmetic cleanup that rides along with it.
 *
 * Filter lists cannot touch YouTube's video ads. The ad and the video are delivered by the same
 * hosts, so there is no request to block and no element to hide: the player decides to show an
 * ad because its own API response told it to. What can be done is what uBlock Origin's
 * scriptlets do — edit that response before the player reads it.
 *
 * Three hooks, all installed before any page script runs:
 *  - `ytInitialPlayerResponse`, the JSON embedded in the page for the first video watched;
 *  - `fetch`, used by the single-page app for every later video;
 *  - `JSON.parse`, a backstop for whatever path the two above miss.
 *
 * Each strips the keys the player uses to schedule ads. A periodic sweep then clicks any skip
 * button and seeks past anything that still slipped through, which also covers ads stitched
 * into the stream server-side, where nothing client-side can remove them cleanly. All of this,
 * plus hiding the ad's own banner/in-feed markup, is gated on "Block YouTube video ads": it's
 * inherently maintenance-prone (YouTube changes these field and class names when it wants to,
 * and may respond with an anti-adblock notice), so it needs to be switchable off as a unit
 * without losing ordinary filter-list blocking.
 *
 * The Shorts shelf and filter chip row are a separate concern -- cosmetic preference, not ad
 * blocking -- so they sit behind their own "Custom site tweaks" setting instead, independent of
 * whether YouTube ad blocking is on. The floating menu button covering a page's own fixed
 * bottom bar is handled generically for every site in [CosmeticFilterBridge], not here.
 */
object YouTubeAdBlock {

    val INJECTION_JS: String = """
        (function () {
          try {
            var host = location.hostname || '';
            if (!/(^|\.)(youtube\.com|youtube-nocookie\.com|youtubekids\.com)${'$'}/.test(host)) {
              return;
            }
            var bridge = window.${CosmeticFilterBridge.BRIDGE_OBJECT_NAME};
            if (!bridge) { return; }
            var adBlockingOn = typeof bridge.youtubeBlockingEnabled === 'function' &&
              bridge.youtubeBlockingEnabled(host);
            var tweaksOn = typeof bridge.customSiteTweaksEnabled === 'function' &&
              bridge.customSiteTweaksEnabled();
            if (!adBlockingOn && !tweaksOn) { return; }
            if (window.__gearslipYouTube) { return; }
            window.__gearslipYouTube = true;

            var styleRules = '';

            if (adBlockingOn) {
              // The fields the player consults to schedule ad breaks.
              var AD_KEYS = [
                'adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'
              ];

              var stripKeys = function (object) {
                if (!object || typeof object !== 'object') { return; }
                for (var i = 0; i < AD_KEYS.length; i++) {
                  if (AD_KEYS[i] in object) {
                    try { delete object[AD_KEYS[i]]; } catch (e) {}
                  }
                }
              };

              // Deliberately shallow. A player response is a large object and walking all of
              // it on every parse would cost more than the ads do.
              var prune = function (payload) {
                if (!payload || typeof payload !== 'object') { return payload; }
                if (Array.isArray(payload)) {
                  for (var i = 0; i < payload.length && i < 50; i++) { prune(payload[i]); }
                  return payload;
                }
                stripKeys(payload);
                if (payload.playerResponse) { stripKeys(payload.playerResponse); }
                if (payload.response) { stripKeys(payload.response); }
                if (payload.playerData) { stripKeys(payload.playerData); }
                return payload;
              };

              // 1. The first video: YouTube assigns this from an inline script in the document.
              try {
                var initial;
                Object.defineProperty(window, 'ytInitialPlayerResponse', {
                  configurable: true,
                  get: function () { return initial; },
                  set: function (value) { initial = prune(value); }
                });
              } catch (e) {}

              // 2. Every later video in the single-page app.
              var originalFetch = window.fetch;
              if (typeof originalFetch === 'function') {
                window.fetch = function (input, init) {
                  var url = '';
                  try {
                    url = (typeof input === 'string') ? input : ((input && input.url) || '');
                  } catch (e) {}
                  var pending = originalFetch.apply(this, arguments);
                  // Only the player call carries ad scheduling. /next is the comments and
                  // related-video payload: large, and re-serialising it buys nothing.
                  if (url.indexOf('/youtubei/v1/player') < 0) {
                    return pending;
                  }
                  return pending.then(function (response) {
                    return response.text().then(function (text) {
                      var body = text;
                      // Cheap string test first: most responses have nothing to strip, and
                      // parsing plus re-serialising a player response is not free.
                      if (text.indexOf('"adPlacements"') >= 0 ||
                          text.indexOf('"playerAds"') >= 0 ||
                          text.indexOf('"adSlots"') >= 0) {
                        try {
                          var data = JSON.parse(text);
                          prune(data);
                          body = JSON.stringify(data);
                        } catch (e) {
                          body = text;
                        }
                      }
                      // Content-Length describes the original bytes, and Content-Encoding a
                      // compression this body no longer has. Copying either onto the edited
                      // body leaves the player waiting on bytes that never arrive.
                      var headers;
                      try {
                        headers = new Headers(response.headers);
                        headers.delete('content-length');
                        headers.delete('content-encoding');
                      } catch (e) {
                        headers = undefined;
                      }
                      return new Response(body, {
                        status: response.status,
                        statusText: response.statusText,
                        headers: headers
                      });
                    }).catch(function () { return response; });
                  });
                };
              }

              // 3. Backstop for any path the first two miss.
              var originalParse = JSON.parse;
              JSON.parse = function (text, reviver) {
                var result = originalParse.apply(this, arguments);
                try { prune(result); } catch (e) {}
                return result;
              };

              // Hide the banner, overlay and in-feed ads, which are ordinary elements. The
              // :has() rules collapse the wrapping feed section too, instead of leaving an
              // empty gap where the ad used to be.
              styleRules +=
                'ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,' +
                'ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,' +
                'ytd-banner-promo-renderer,ytd-statement-banner-renderer,' +
                'ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,' +
                'ytm-promoted-video-renderer,ytm-companion-ad-renderer,' +
                'ytm-promoted-sparkles-web-renderer,ytm-search-promoted-video-renderer,' +
                '.ytp-ad-overlay-container,.ytp-ad-overlay-slot,.ytp-ad-message-container,' +
                '#masthead-ad,#player-ads,' +
                'ytm-item-section-renderer:has(>*>ytm-companion-ad-renderer),' +
                'ytm-item-section-renderer:has(>*>ytm-promoted-sparkles-web-renderer),' +
                'ytm-item-section-renderer:has(>*>ytm-promoted-video-renderer),' +
                'ytm-item-section-renderer:has(>*>ytm-search-promoted-video-renderer),' +
                'ytm-item-section-renderer:has(>*>ytd-ad-slot-renderer),' +
                // The "desktop site" grid feed uses different wrapper elements entirely: each
                // card is a ytd-rich-item-renderer grid cell, so hiding just the ad renderer
                // inside it still leaves an empty cell-shaped hole in the grid.
                'ytd-rich-item-renderer:has(ytd-display-ad-renderer),' +
                'ytd-rich-item-renderer:has(ytd-ad-slot-renderer),' +
                'ytd-rich-item-renderer:has(ytd-promoted-sparkles-web-renderer),' +
                'ytd-rich-item-renderer:has(ytd-statement-banner-renderer),' +
                'ytd-rich-item-renderer:has(ytd-banner-promo-renderer),' +
                // Ads can also occupy a whole grid row, or a section above/between rows, not
                // just a single cell -- same empty-hole problem, one level up each time.
                'ytd-rich-grid-row:has(ytd-display-ad-renderer),' +
                'ytd-rich-grid-row:has(ytd-ad-slot-renderer),' +
                'ytd-rich-grid-row:has(ytd-promoted-sparkles-web-renderer),' +
                'ytd-rich-grid-row:has(ytd-statement-banner-renderer),' +
                'ytd-rich-grid-row:has(ytd-banner-promo-renderer),' +
                'ytd-rich-section-renderer:has(ytd-ad-slot-renderer),' +
                'ytd-rich-section-renderer:has(ytd-display-ad-renderer),' +
                'ytd-rich-section-renderer:has(ytd-statement-banner-renderer),' +
                'ytd-rich-section-renderer:has(ytd-banner-promo-renderer),' +
                'ytd-ad-slot-renderer,ytd-primetime-promo-renderer' +
                '{display:none!important}';

              // Sweep for anything still playing: press skip, or seek to the end of the ad.
              var adTicks = 0;
              var sweep = function () {
                try {
                  var skip = document.querySelector(
                    '.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,' +
                    '.ytp-ad-skip-button-container button'
                  );
                  if (skip) { skip.click(); }
                  var player = document.querySelector('.html5-video-player');
                  if (player && player.classList.contains('ad-showing')) {
                    // Only seek once the ad state has held for two ticks. Seeking during the
                    // player's own transition makes it re-buffer, which looks like a stall.
                    adTicks++;
                    var video = player.querySelector('video');
                    if (adTicks >= 2 && video && isFinite(video.duration) && video.duration > 0) {
                      video.currentTime = video.duration;
                    }
                  } else {
                    adTicks = 0;
                  }
                } catch (e) {}
              };
              setInterval(sweep, 700);
            }

            if (tweaksOn) {
              styleRules +=
                // The mobile site's fixed Home/Shorts/You bar and the "desktop site" grid's
                // reel shelf section it sits inside of.
                'ytm-pivot-bar-renderer,' +
                'ytd-rich-section-renderer:has(ytd-reel-shelf-renderer),' +
                // Shorts: a different, faster-cut format than the rest of the feed, and its
                // vertical-video shelf doesn't fit a landscape car display well either way.
                'ytm-reel-shelf-renderer,ytm-rich-shelf-renderer:has(ytm-reel-item-renderer),' +
                'ytm-reel-item-renderer,ytd-reel-shelf-renderer,' +
                // The "All / Podcasts / News / ..." filter chip row above the feed.
                'ytm-chip-bar-renderer,ytm-feed-filter-chip-bar-renderer' +
                '{display:none!important}';
            }

            if (styleRules) {
              var style = document.createElement('style');
              style.setAttribute('data-gearslip', 'youtube');
              style.textContent = styleRules;
              var root = document.head || document.documentElement;
              if (root) { root.appendChild(style); }
            }
          } catch (e) {}
        })();
    """.trimIndent()
}
