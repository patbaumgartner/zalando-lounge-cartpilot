package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Table("profiles")
class ProfileJdbcEntity {

	@Id
	Long id;

	String name;

	String gender;

	boolean active;

	BigDecimal maxPriceShoes;

	BigDecimal maxPriceJackets;

	BigDecimal maxPriceClothing;

	String brandTier1;

	String brandTier2;

	String brandAliases;

	LocalDateTime createdAt;

	@MappedCollection(idColumn = "profile_id")
	Set<ProfileSizeJdbcEntity> sizes = new LinkedHashSet<>();

}
