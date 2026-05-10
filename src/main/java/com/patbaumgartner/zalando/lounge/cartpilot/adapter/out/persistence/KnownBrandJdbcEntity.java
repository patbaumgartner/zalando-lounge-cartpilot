package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("known_brands")
class KnownBrandJdbcEntity {

	@Id
	Long id;

	String brandName;

	LocalDateTime firstSeenAt;

}
