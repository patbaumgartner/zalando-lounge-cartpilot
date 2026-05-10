package com.patbaumgartner.zalando.lounge.cartpilot.testdata;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test data builder (Object Mother) for {@link Profile}. Use like:
 * {@code ProfileTestData.aProfile().withTier1Brand("Mammut").build()}
 */
public class ProfileTestData {

	private Long id = 1L;

	private String name = "TestUser";

	private Gender gender = Gender.MEN;

	private boolean active = true;

	private final Map<Category, String> sizes = new EnumMap<>(Category.class);

	private final List<String> brandTier1 = new ArrayList<>();

	private final List<String> brandTier2 = new ArrayList<>();

	private final Map<String, String> brandAliases = new HashMap<>();

	private BigDecimal maxPriceShoes = new BigDecimal("300");

	private BigDecimal maxPriceJackets = new BigDecimal("500");

	private BigDecimal maxPriceClothing = new BigDecimal("250");

	private ProfileTestData() {
		// sensible defaults
		sizes.put(Category.SHOES, "43");
		sizes.put(Category.SHIRTS, "L");
		sizes.put(Category.JACKETS, "52");
	}

	public static ProfileTestData aProfile() {
		return new ProfileTestData();
	}

	public static Profile pat() {
		return aProfile().withName("Pat")
			.withGender(Gender.MEN)
			.withTier1Brand("Mammut")
			.withTier1Brand("Arc'teryx")
			.withTier2Brand("Jack Wolfskin")
			.build();
	}

	public ProfileTestData withId(Long id) {
		this.id = id;
		return this;
	}

	public ProfileTestData withName(String name) {
		this.name = name;
		return this;
	}

	public ProfileTestData withGender(Gender gender) {
		this.gender = gender;
		return this;
	}

	public ProfileTestData inactive() {
		this.active = false;
		return this;
	}

	public ProfileTestData withSize(Category category, String size) {
		this.sizes.put(category, size);
		return this;
	}

	public ProfileTestData withoutSize(Category category) {
		this.sizes.remove(category);
		return this;
	}

	public ProfileTestData withTier1Brand(String brand) {
		this.brandTier1.add(brand);
		return this;
	}

	public ProfileTestData withTier2Brand(String brand) {
		this.brandTier2.add(brand);
		return this;
	}

	public ProfileTestData withBrandAlias(String alias, String canonical) {
		this.brandAliases.put(alias, canonical);
		return this;
	}

	public ProfileTestData withMaxPriceShoes(BigDecimal price) {
		this.maxPriceShoes = price;
		return this;
	}

	public ProfileTestData withMaxPriceJackets(BigDecimal price) {
		this.maxPriceJackets = price;
		return this;
	}

	public ProfileTestData withMaxPriceClothing(BigDecimal price) {
		this.maxPriceClothing = price;
		return this;
	}

	public Profile build() {
		return new Profile(id, name, gender, active, Map.copyOf(sizes), List.copyOf(brandTier1),
				List.copyOf(brandTier2), Map.copyOf(brandAliases), maxPriceShoes, maxPriceJackets, maxPriceClothing);
	}

}
