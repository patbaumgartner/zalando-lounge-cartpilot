package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

/**
 * Outcome of a keep-alive refresh, which removes the article and immediately re-adds it
 * to reset Zalando's server-side reservation timer.
 *
 * <p>
 * {@code removalConfirmed} is what makes a failed re-add interpretable. If the removal
 * never happened the previous hold is untouched and the reservation is still live; if the
 * removal succeeded the old hold is gone for good, so a failed re-add means the
 * reservation is lost — even when the failure was a bot-wall rejection that says nothing
 * about stock.
 *
 * @param removalConfirmed whether the article is known to have left the basket
 * @param addResult outcome of putting it back
 */
public record CartRefreshResult(boolean removalConfirmed, CartAddResult addResult) {

	public boolean isRefreshed() {
		return addResult.isAdded();
	}

	/** True when the article is still held, either re-added or never actually removed. */
	public boolean holdSurvived() {
		return addResult.isAdded() || !removalConfirmed;
	}

	public boolean isBlocked() {
		return addResult.isBlocked();
	}

	public String describe() {
		if (addResult.isAdded()) {
			return addResult.describe();
		}
		return removalConfirmed ? addResult.describe() + " (the previous hold was already released)"
				: addResult.describe() + " (the previous hold is untouched)";
	}

	public String detail() {
		return addResult.detail();
	}

}
