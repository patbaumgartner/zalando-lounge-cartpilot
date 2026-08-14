package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Page;

import java.util.Map;

/**
 * The browser-native HTTP calls the Zalando clients issue from inside a page.
 *
 * <p>
 * Extracted so the JSON clients built on top ({@link CartApi}, {@link CampaignScraper})
 * can be exercised against scripted responses — rate limits, bot walls, logged-out HTML
 * under HTTP 200, malformed bodies — without starting Chromium. The production
 * implementation is {@link InPageHttpClient}.
 */
interface PageHttpClient {

	InPageResponse get(Page page, String url);

	InPageResponse delete(Page page, String url);

	InPageResponse postJson(Page page, String url, Map<String, Object> body);

}
