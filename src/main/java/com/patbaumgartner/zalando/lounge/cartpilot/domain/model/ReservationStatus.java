package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

public enum ReservationStatus {

	PENDING, IN_CART, PURCHASE_INITIATED, REJECTED, EXPIRED, OUT_OF_STOCK,

	/**
	 * The article matched and was in stock, but the shop's bot protection refused the
	 * basket call. It never made it into the cart and has to be grabbed by hand — so it
	 * stays on the link list instead of being written off as sold out.
	 */
	BLOCKED,

	/**
	 * The basket call failed for a reason that says nothing about stock — a transport
	 * error, an unparseable response, or an unexpected status. Kept distinct from
	 * {@link #OUT_OF_STOCK} so a broken scan is not silently reported as a sold-out
	 * article.
	 */
	FAILED;

	/**
	 * Statuses whose product is still worth opening in the browser: either it is held for
	 * us right now, or the bot has not managed to hold it and a human still can.
	 */
	public boolean isActionable() {
		return this == PENDING || this == IN_CART || this == BLOCKED;
	}

}
