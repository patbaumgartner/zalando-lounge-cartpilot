package com.patbaumgartner.zalando.lounge.cartpilot.testdata;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;

import java.time.LocalDateTime;

/**
 * Test data builder (Object Mother) for {@link ProductReservation}.
 */
public class ReservationTestData {

	private Long id = 1L;

	private Long productId = 1L;

	private Long profileId = 1L;

	private String size = "52";

	private Decision decision = Decision.AUTO_RESERVE;

	private ReservationStatus status = ReservationStatus.PENDING;

	private int score = 50;

	private Integer telegramMsgId;

	private final LocalDateTime cartAddedAt = null;

	private final LocalDateTime cartExpiresAt = null;

	private final LocalDateTime createdAt = LocalDateTime.now();

	private ReservationTestData() {
	}

	public static ReservationTestData aReservation() {
		return new ReservationTestData();
	}

	public static ProductReservation pendingReservation() {
		return ProductReservation.pending(1L, 1L, "52", Decision.AUTO_RESERVE, 50);
	}

	public static ProductReservation inCartReservation() {
		var r = ProductReservation.pending(1L, 1L, "52", Decision.AUTO_RESERVE, 50);
		r.markInCart(LocalDateTime.now(), 20);
		r.setTelegramMsgId(42);
		return r;
	}

	public ReservationTestData withId(Long id) {
		this.id = id;
		return this;
	}

	public ReservationTestData withProductId(Long productId) {
		this.productId = productId;
		return this;
	}

	public ReservationTestData withProfileId(Long profileId) {
		this.profileId = profileId;
		return this;
	}

	public ReservationTestData withSize(String size) {
		this.size = size;
		return this;
	}

	public ReservationTestData withDecision(Decision decision) {
		this.decision = decision;
		return this;
	}

	public ReservationTestData withStatus(ReservationStatus status) {
		this.status = status;
		return this;
	}

	public ReservationTestData withScore(int score) {
		this.score = score;
		return this;
	}

	public ReservationTestData withTelegramMsgId(Integer msgId) {
		this.telegramMsgId = msgId;
		return this;
	}

	public ProductReservation build() {
		return new ProductReservation(id, productId, profileId, size, decision, status, score, telegramMsgId,
				cartAddedAt, cartExpiresAt, createdAt);
	}

}
