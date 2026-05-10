package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Records one profile's decision for one discovered product. Status machine: PENDING →
 * IN_CART → PURCHASE_INITIATED | REJECTED | EXPIRED PENDING → OUT_OF_STOCK
 */
public class ProductReservation {

	private Long id;

	private final Long productId;

	private final Long profileId;

	private final String size;

	private final Decision decision;

	private ReservationStatus status;

	private final int score;

	private Integer telegramMsgId;

	private LocalDateTime cartAddedAt;

	private LocalDateTime cartExpiresAt;

	private final LocalDateTime createdAt;

	public ProductReservation(Long id, Long productId, Long profileId, String size, Decision decision,
			ReservationStatus status, int score, Integer telegramMsgId, LocalDateTime cartAddedAt,
			LocalDateTime cartExpiresAt, LocalDateTime createdAt) {
		this.id = id;
		this.productId = Objects.requireNonNull(productId, "productId");
		this.profileId = Objects.requireNonNull(profileId, "profileId");
		this.size = size;
		this.decision = Objects.requireNonNull(decision, "decision");
		this.status = Objects.requireNonNull(status, "status");
		this.score = score;
		this.telegramMsgId = telegramMsgId;
		this.cartAddedAt = cartAddedAt;
		this.cartExpiresAt = cartExpiresAt;
		this.createdAt = createdAt;
	}

	/** Factory for a fresh reservation just before adding to cart. */
	public static ProductReservation pending(Long productId, Long profileId, String size, Decision decision,
			int score) {
		return new ProductReservation(null, productId, profileId, size, decision, ReservationStatus.PENDING, score,
				null, null, null, LocalDateTime.now());
	}

	// ── State transitions ──────────────────────────────────────

	public void markInCart(LocalDateTime now, int expiryMinutes) {
		this.status = ReservationStatus.IN_CART;
		this.cartAddedAt = now;
		this.cartExpiresAt = now.plusMinutes(expiryMinutes);
	}

	public void renewCartExpiry(int expiryMinutes) {
		this.cartExpiresAt = LocalDateTime.now().plusMinutes(expiryMinutes);
	}

	public void markPurchaseInitiated() {
		this.status = ReservationStatus.PURCHASE_INITIATED;
	}

	public void reject() {
		this.status = ReservationStatus.REJECTED;
	}

	public void expire() {
		this.status = ReservationStatus.EXPIRED;
	}

	public void markOutOfStock() {
		this.status = ReservationStatus.OUT_OF_STOCK;
	}

	public boolean isCartExpired() {
		return cartExpiresAt != null && LocalDateTime.now().isAfter(cartExpiresAt);
	}

	public boolean isInCart() {
		return status == ReservationStatus.IN_CART;
	}

	// ── Accessors ──────────────────────────────────────────────

	public Long id() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long productId() {
		return productId;
	}

	public Long profileId() {
		return profileId;
	}

	public String size() {
		return size;
	}

	public Decision decision() {
		return decision;
	}

	public ReservationStatus status() {
		return status;
	}

	public int score() {
		return score;
	}

	public Integer telegramMsgId() {
		return telegramMsgId;
	}

	public void setTelegramMsgId(Integer telegramMsgId) {
		this.telegramMsgId = telegramMsgId;
	}

	public LocalDateTime cartAddedAt() {
		return cartAddedAt;
	}

	public LocalDateTime cartExpiresAt() {
		return cartExpiresAt;
	}

	public LocalDateTime createdAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ProductReservation r)) {
			return false;
		}
		return Objects.equals(id, r.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

}
