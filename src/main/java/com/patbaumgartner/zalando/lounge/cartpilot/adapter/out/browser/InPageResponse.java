package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

/**
 * Result of a single browser-native call issued from inside a Patchright page.
 *
 * @param status the HTTP status, or {@code 0} when no response was produced at all
 * @param body the response body, already truncated to a sane size
 * @param error the JS/transport error message when {@link #isTransportFailure()}
 */
record InPageResponse(int status, String body, String error) {

	/** Longest body excerpt kept for logs — enough to see an Akamai rejection reason. */
	private static final int SNIPPET_LIMIT = 300;

	static InPageResponse transportError(String error) {
		return new InPageResponse(0, "", (error == null || error.isBlank()) ? "unknown error" : error);
	}

	boolean ok() {
		return status >= 200 && status < 300;
	}

	/** Akamai's bot wall answers {@code 403}; its rate limiter answers {@code 429}. */
	boolean isBotWall() {
		return status == 403;
	}

	boolean isRateLimited() {
		return status == 429;
	}

	/** No HTTP response at all: CSP block, aborted fetch, navigation, or a dead page. */
	boolean isTransportFailure() {
		return status <= 0;
	}

	/**
	 * True when the body is a JSON document. Zalando answers logged-out API requests with
	 * an HTML login/interstitial page under a {@code 200}, so the status alone cannot
	 * tell an authenticated response from a logged-out one.
	 */
	boolean isJson() {
		String trimmed = body == null ? "" : body.strip();
		return trimmed.startsWith("{") || trimmed.startsWith("[");
	}

	/** Compact "HTTP 403" / "transport error: ..." string for logs and Telegram. */
	String describe() {
		return isTransportFailure() ? "transport error: " + error : "HTTP " + status;
	}

	/**
	 * Short, tag-stripped, whitespace-collapsed excerpt of the body. Keeps an
	 * {@code edge_error:halt} rejection visible in the log without dumping a full
	 * authenticated HTML page (which can carry personal data).
	 */
	String bodySnippet() {
		if (body == null || body.isBlank()) {
			return "";
		}
		String plain = body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
		return plain.length() <= SNIPPET_LIMIT ? plain : plain.substring(0, SNIPPET_LIMIT) + "…";
	}

}
