package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

/**
 * Outcome of a single cart add attempt, including the diagnostic detail needed to explain
 * the failure in Telegram.
 *
 * @param outcome what happened
 * @param httpStatus the last HTTP status seen, or {@code 0} when no request was issued
 * @param detail short human-readable reason, safe to post to the group
 */
public record CartAddResult(CartAddOutcome outcome, int httpStatus, String detail) {

	public static CartAddResult added() {
		return new CartAddResult(CartAddOutcome.ADDED, 200, "added to basket");
	}

	public static CartAddResult sizeUnavailable(String detail) {
		return new CartAddResult(CartAddOutcome.SIZE_UNAVAILABLE, 0, detail);
	}

	public static CartAddResult blocked(int httpStatus, String detail) {
		return new CartAddResult(CartAddOutcome.BLOCKED, httpStatus, detail);
	}

	public static CartAddResult failed(int httpStatus, String detail) {
		return new CartAddResult(CartAddOutcome.FAILED, httpStatus, detail);
	}

	public boolean isAdded() {
		return outcome.isAdded();
	}

	public boolean isBlocked() {
		return outcome.isBlocked();
	}

	/** Compact "OUTCOME (HTTP 403): detail" string for logs and Telegram diagnostics. */
	public String describe() {
		var sb = new StringBuilder(outcome.name());
		if (httpStatus > 0) {
			sb.append(" (HTTP ").append(httpStatus).append(')');
		}
		if (detail != null && !detail.isBlank()) {
			sb.append(": ").append(detail);
		}
		return sb.toString();
	}

}
