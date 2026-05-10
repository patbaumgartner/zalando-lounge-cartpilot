package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

import java.time.LocalDate;

/**
 * Represents a Zalando Lounge campaign scraped from the SSR HTML. Immutable — just a data
 * bag from the browser.
 */
public record Campaign(String campaignId, String title, LocalDate startsAt, String campaignUrl) {
}
