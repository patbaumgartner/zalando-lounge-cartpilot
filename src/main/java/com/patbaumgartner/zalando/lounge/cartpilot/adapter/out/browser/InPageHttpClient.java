package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Issues Zalando's JSON API calls from <em>inside</em> a Patchright page instead of
 * through Playwright's {@link com.microsoft.playwright.APIRequestContext}.
 *
 * <p>
 * {@code context.request()} / {@code page.request()} share the browser's cookie jar but
 * are executed by Playwright's Node HTTP client, not by Chromium: Node's TLS handshake,
 * Node's header order, and none of the client hints or {@code Sec-Fetch-*} metadata a
 * real browser sends. Akamai BotManager fingerprints exactly that mismatch — the same
 * cookies arriving over an entirely different client — and answers {@code 403}, which is
 * what refused the article lookup and the basket calls.
 *
 * <p>
 * A {@code fetch()} evaluated in the page goes out over Chromium's own network service:
 * real TLS/HTTP fingerprint, browser-generated {@code Origin}, {@code Referer},
 * {@code Sec-Fetch-*} and client hints, and the live Akamai sensor cookies of a document
 * whose sensor script is still running. Only genuine application headers are set here;
 * {@code Origin}, {@code Referer}, {@code Cookie} and {@code Sec-*} are forbidden header
 * names that the browser must fill in itself.
 */
class InPageHttpClient {

	private static final Logger log = LoggerFactory.getLogger(InPageHttpClient.class);

	private static final String FETCH_JS = """
			async ({ url, method, body, timeoutMs }) => {
			  const controller = new AbortController();
			  const timer = setTimeout(() => controller.abort(), timeoutMs);
			  try {
			    const headers = { 'Accept': 'application/json' };
			    const init = { method, credentials: 'same-origin', headers, signal: controller.signal };
			    if (body !== null && body !== undefined) {
			      headers['Content-Type'] = 'application/json';
			      init.body = JSON.stringify(body);
			    }
			    const response = await fetch(url, init);
			    let text = '';
			    try {
			      text = await response.text();
			    } catch (ignored) {
			      text = '';
			    }
			    return { status: response.status, body: text, error: '' };
			  } catch (error) {
			    return { status: 0, body: '', error: String((error && error.message) || error) };
			  } finally {
			    clearTimeout(timer);
			  }
			}
			""";

	private final int timeoutMs;

	InPageHttpClient(long timeoutMs) {
		// Playwright's argument serializer rejects Long, so the evaluate payload has to
		// carry an int.
		this.timeoutMs = (int) Math.min(Integer.MAX_VALUE, timeoutMs > 0 ? timeoutMs : 30_000L);
	}

	InPageResponse get(Page page, String url) {
		return send(page, url, "GET", null);
	}

	InPageResponse delete(Page page, String url) {
		return send(page, url, "DELETE", null);
	}

	InPageResponse postJson(Page page, String url, Map<String, Object> body) {
		return send(page, url, "POST", body);
	}

	private InPageResponse send(Page page, String url, String method, Map<String, Object> body) {
		warnOnCrossOrigin(page, url, method);

		var arguments = new HashMap<String, Object>();
		arguments.put("url", url);
		arguments.put("method", method);
		arguments.put("body", body);
		arguments.put("timeoutMs", timeoutMs);

		try {
			Object raw = page.evaluate(FETCH_JS, arguments);
			if (!(raw instanceof Map<?, ?> result)) {
				return InPageResponse.transportError("in-page fetch returned no result object");
			}
			int status = result.get("status") instanceof Number number ? number.intValue() : 0;
			String responseBody = stringValue(result.get("body"));
			String error = stringValue(result.get("error"));
			var response = status > 0 ? new InPageResponse(status, responseBody, "")
					: InPageResponse.transportError(error);
			if (!response.ok()) {
				log.debug("In-page {} {} → {} ({})", method, url, response.describe(), response.bodySnippet());
			}
			return response;
		}
		catch (Exception e) {
			// A navigation triggered by the page itself destroys the execution context
			// mid-evaluate; callers retry, so this stays a transport failure rather than
			// an exception.
			return InPageResponse.transportError(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	/**
	 * A same-origin call needs no CORS preflight and carries the session cookies. A page
	 * that is still blank has an opaque origin, so every fetch from it fails with a bare
	 * {@code TypeError: Failed to fetch} — worth naming explicitly, because the cause is
	 * invisible in the error.
	 */
	private void warnOnCrossOrigin(Page page, String url, String method) {
		String pageOrigin = origin(page.url());
		String targetOrigin = origin(url);
		if (pageOrigin == null) {
			log.warn("In-page {} {} runs on a page with no origin ({}); it must be navigated to the site first", method,
					targetOrigin, page.url());
			return;
		}
		if (targetOrigin != null && !pageOrigin.equals(targetOrigin)) {
			log.warn("In-page {} {} is cross-origin: the page is on {} — cookies and Sec-Fetch-Site will differ",
					method, targetOrigin, pageOrigin);
		}
	}

	private static String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String origin(String url) {
		try {
			var uri = URI.create(url);
			if (uri.getScheme() == null || uri.getHost() == null) {
				return null;
			}
			return uri.getPort() > 0 ? uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort()
					: uri.getScheme() + "://" + uri.getHost();
		}
		catch (Exception e) {
			return null;
		}
	}

}
