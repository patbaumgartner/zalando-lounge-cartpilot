package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

/**
 * Why a cart add succeeded or failed. A failed add used to collapse into a plain
 * {@code false}, which made a bot-wall rejection indistinguishable from a genuinely
 * sold-out size — the two need very different follow-up (retry / manual grab vs. drop).
 */
public enum CartAddOutcome {

	/** The article is confirmed present in the basket. */
	ADDED,

	/** The requested size is not purchasable (sold out or no longer offered). */
	SIZE_UNAVAILABLE,

	/**
	 * The shop's bot protection (Akamai BotManager / Cloudflare) rejected the request.
	 * The article itself is probably still buyable by hand.
	 */
	BLOCKED,

	/** Anything else: transport error, unexpected status, unconfirmed basket state. */
	FAILED;

	public boolean isAdded() {
		return this == ADDED;
	}

	public boolean isBlocked() {
		return this == BLOCKED;
	}

}
