package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table("discovered_products")
class DiscoveredProductJdbcEntity {

	@Id
	Long id;

	String campaignId;

	String brand;

	String name;

	String category;

	String gender;

	String sizesAvailable; // comma-separated

	BigDecimal originalPrice;

	BigDecimal loungePrice;

	int discountPct;

	String productUrl;

	String status;

	LocalDateTime discoveredAt;

}
