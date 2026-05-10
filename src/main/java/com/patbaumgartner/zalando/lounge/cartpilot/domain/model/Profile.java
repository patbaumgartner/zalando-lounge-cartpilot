package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root representing a family member whose shopping preferences CartPilot
 * monitors. All business rules live here — no persistence concerns.
 */
public class Profile {

	private final Long id;

	private final String name;

	private final Gender gender;

	private boolean active;

	private final Map<Category, String> sizes;

	private final List<String> brandTier1;

	private final List<String> brandTier2;

	private final Map<String, String> brandAliases;

	private final BigDecimal maxPriceShoes;

	private final BigDecimal maxPriceJackets;

	private final BigDecimal maxPriceClothing;

	public Profile(Long id, String name, Gender gender, boolean active, Map<Category, String> sizes,
			List<String> brandTier1, List<String> brandTier2, Map<String, String> brandAliases,
			BigDecimal maxPriceShoes, BigDecimal maxPriceJackets, BigDecimal maxPriceClothing) {
		this.id = id;
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.gender = Objects.requireNonNull(gender, "gender must not be null");
		this.active = active;
		this.sizes = Collections.unmodifiableMap(new EnumMap<>(sizes));
		this.brandTier1 = Collections.unmodifiableList(new ArrayList<>(brandTier1));
		this.brandTier2 = Collections.unmodifiableList(new ArrayList<>(brandTier2));
		this.brandAliases = Collections.unmodifiableMap(new HashMap<>(brandAliases));
		this.maxPriceShoes = maxPriceShoes;
		this.maxPriceJackets = maxPriceJackets;
		this.maxPriceClothing = maxPriceClothing;
	}

	// ── Business behaviour ─────────────────────────────────────

	public Optional<String> sizeFor(Category category) {
		return Optional.ofNullable(sizes.get(category));
	}

	public Optional<BigDecimal> maxPriceFor(Category category) {
		return switch (category) {
			case SHOES -> Optional.ofNullable(maxPriceShoes);
			case JACKETS -> Optional.ofNullable(maxPriceJackets);
			default -> Optional.ofNullable(maxPriceClothing);
		};
	}

	/** Returns the canonical brand name, resolving aliases if defined. */
	public String resolveAlias(String rawBrand) {
		return brandAliases.getOrDefault(rawBrand, rawBrand);
	}

	public void activate() {
		this.active = true;
	}

	public void deactivate() {
		this.active = false;
	}

	public Profile withSize(Category category, String size) {
		var updated = new EnumMap<>(sizes);
		updated.put(category, size);
		return new Profile(id, name, gender, active, updated, brandTier1, brandTier2, brandAliases, maxPriceShoes,
				maxPriceJackets, maxPriceClothing);
	}

	public Profile withMaxPrice(Category category, BigDecimal price) {
		return switch (category) {
			case SHOES -> new Profile(id, name, gender, active, sizes, brandTier1, brandTier2, brandAliases, price,
					maxPriceJackets, maxPriceClothing);
			case JACKETS -> new Profile(id, name, gender, active, sizes, brandTier1, brandTier2, brandAliases,
					maxPriceShoes, price, maxPriceClothing);
			default -> new Profile(id, name, gender, active, sizes, brandTier1, brandTier2, brandAliases, maxPriceShoes,
					maxPriceJackets, price);
		};
	}

	public Profile withBrandInTier(BrandTier tier, String brand) {
		var t1 = new ArrayList<>(brandTier1);
		var t2 = new ArrayList<>(brandTier2);
		switch (tier) {
			case TIER_1 -> t1.add(brand);
			case TIER_2 -> t2.add(brand);
		}
		return new Profile(id, name, gender, active, sizes, t1, t2, brandAliases, maxPriceShoes, maxPriceJackets,
				maxPriceClothing);
	}

	public Profile withBrandRemoved(String brand) {
		var t1 = new ArrayList<>(brandTier1);
		var t2 = new ArrayList<>(brandTier2);
		t1.remove(brand);
		t2.remove(brand);
		return new Profile(id, name, gender, active, sizes, t1, t2, brandAliases, maxPriceShoes, maxPriceJackets,
				maxPriceClothing);
	}

	// ── Accessors ──────────────────────────────────────────────

	public Long id() {
		return id;
	}

	public String name() {
		return name;
	}

	public Gender gender() {
		return gender;
	}

	public boolean active() {
		return active;
	}

	public Map<Category, String> sizes() {
		return sizes;
	}

	public List<String> brandTier1() {
		return brandTier1;
	}

	public List<String> brandTier2() {
		return brandTier2;
	}

	public Map<String, String> brandAliases() {
		return brandAliases;
	}

	public BigDecimal maxPriceShoes() {
		return maxPriceShoes;
	}

	public BigDecimal maxPriceJackets() {
		return maxPriceJackets;
	}

	public BigDecimal maxPriceClothing() {
		return maxPriceClothing;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Profile p)) {
			return false;
		}
		return Objects.equals(id, p.id) && Objects.equals(name, p.name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, name);
	}

	@Override
	public String toString() {
		return "Profile{name='%s', gender=%s, active=%s}".formatted(name, gender, active);
	}

}
