package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("purchased_items")
class PurchasedItemJdbcEntity {

	@Id
	Long id;

	Long profileId;

	Long productId;

	LocalDateTime purchasedAt;

	String purchasedByUsername;

}
