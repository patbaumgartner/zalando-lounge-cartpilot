package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("product_reservations")
class ProductReservationJdbcEntity {

	@Id
	Long id;

	Long productId;

	Long profileId;

	String size;

	String decision;

	String status;

	int score;

	Integer telegramMsgId;

	LocalDateTime cartAddedAt;

	LocalDateTime cartExpiresAt;

	LocalDateTime createdAt;

}
