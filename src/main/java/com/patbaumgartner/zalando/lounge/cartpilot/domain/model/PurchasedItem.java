package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

import java.time.LocalDateTime;
import java.util.Objects;

/** Tracks a confirmed purchase so the same item is never recommended again. */
public class PurchasedItem {

	private final Long id;

	private final Long profileId;

	private final Long productId;

	private final LocalDateTime purchasedAt;

	private final String purchasedByUsername;

	public PurchasedItem(Long id, Long profileId, Long productId, LocalDateTime purchasedAt,
			String purchasedByUsername) {
		this.id = id;
		this.profileId = Objects.requireNonNull(profileId, "profileId");
		this.productId = Objects.requireNonNull(productId, "productId");
		this.purchasedAt = purchasedAt != null ? purchasedAt : LocalDateTime.now();
		this.purchasedByUsername = purchasedByUsername;
	}

	public static PurchasedItem of(Long profileId, Long productId, String byUsername) {
		return new PurchasedItem(null, profileId, productId, LocalDateTime.now(), byUsername);
	}

	public Long id() {
		return id;
	}

	public Long profileId() {
		return profileId;
	}

	public Long productId() {
		return productId;
	}

	public LocalDateTime purchasedAt() {
		return purchasedAt;
	}

	public String purchasedByUsername() {
		return purchasedByUsername;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PurchasedItem p)) {
			return false;
		}
		return Objects.equals(id, p.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

}
