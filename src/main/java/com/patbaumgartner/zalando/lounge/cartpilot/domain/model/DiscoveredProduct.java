package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A product found during a campaign scan. Status transitions: DISCOVERED → SCORED →
 * PROCESSED
 */
public class DiscoveredProduct {

	private static final Pattern ARTICLE_KEY_PATTERN = Pattern.compile("/articles/([^/?#]+)");

	private final Long id;

	private final String campaignId;

	private final String brand;

	private final String name;

	private final Category category;

	private Gender gender;

	private List<String> sizesAvailable;

	private final BigDecimal originalPrice;

	private final BigDecimal loungePrice;

	private final int discountPct;

	private final String productUrl;

	private ProductStatus status;

	private final LocalDateTime discoveredAt;

	public DiscoveredProduct(Long id, String campaignId, String brand, String name, Category category, Gender gender,
			List<String> sizesAvailable, BigDecimal originalPrice, BigDecimal loungePrice, int discountPct,
			String productUrl, ProductStatus status, LocalDateTime discoveredAt) {
		this.id = id;
		this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
		this.brand = Objects.requireNonNull(brand, "brand");
		this.name = Objects.requireNonNull(name, "name");
		this.category = Objects.requireNonNull(category, "category");
		this.gender = Objects.requireNonNull(gender, "gender");
		this.sizesAvailable = List.copyOf(sizesAvailable);
		this.originalPrice = Objects.requireNonNull(originalPrice, "originalPrice");
		this.loungePrice = Objects.requireNonNull(loungePrice, "loungePrice");
		this.discountPct = discountPct;
		this.productUrl = Objects.requireNonNull(productUrl, "productUrl");
		this.status = Objects.requireNonNull(status, "status");
		this.discoveredAt = discoveredAt;
	}

	// ── Business behaviour ─────────────────────────────────────

	public boolean hasSizeAvailable(String size) {
		if (size == null || size.isBlank()) {
			return false;
		}
		return sizesAvailable.stream().anyMatch(s -> s.equalsIgnoreCase(size.trim()));
	}

	/**
	 * Stable identity of the Zalando article behind this row.
	 *
	 * <p>
	 * Every scan inserts its products afresh, so the database id identifies a
	 * <em>sighting</em>, not an article: comparing ids across days never matches. The
	 * article sku in the product URL is what stays the same when the same article shows
	 * up again tomorrow.
	 */
	public String articleKey() {
		return articleKeyOf(productUrl);
	}

	public static String articleKeyOf(String productUrl) {
		if (productUrl == null || productUrl.isBlank()) {
			return "";
		}
		var matcher = ARTICLE_KEY_PATTERN.matcher(productUrl);
		return matcher.find() ? matcher.group(1) : productUrl;
	}

	public boolean isGenderCompatibleWith(Gender profileGender) {
		return gender.isCompatibleWith(profileGender);
	}

	public boolean isPriceUnder(BigDecimal maxPrice) {
		return maxPrice == null || loungePrice.compareTo(maxPrice) <= 0;
	}

	/**
	 * Enriches the product with sizes scraped from its detail page. Campaign listing
	 * pages do not expose sizes, so these are fetched lazily for brand/price candidates.
	 */
	public void applyAvailableSizes(List<String> sizes) {
		this.sizesAvailable = List.copyOf(sizes);
	}

	/**
	 * Enriches the product with the gender resolved from its detail page. Campaign
	 * listing cards do not expose gender, so products are discovered as
	 * {@link Gender#UNISEX} and the real gender is applied lazily for brand/price
	 * candidates before the gender gate is re-evaluated.
	 */
	public void applyGender(Gender resolvedGender) {
		this.gender = Objects.requireNonNull(resolvedGender, "resolvedGender");
	}

	public void markScored() {
		this.status = ProductStatus.SCORED;
	}

	public void markProcessed() {
		this.status = ProductStatus.PROCESSED;
	}

	// ── Accessors ──────────────────────────────────────────────

	public Long id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public String brand() {
		return brand;
	}

	public String name() {
		return name;
	}

	public Category category() {
		return category;
	}

	public Gender gender() {
		return gender;
	}

	public List<String> sizesAvailable() {
		return sizesAvailable;
	}

	public BigDecimal originalPrice() {
		return originalPrice;
	}

	public BigDecimal loungePrice() {
		return loungePrice;
	}

	public int discountPct() {
		return discountPct;
	}

	public String productUrl() {
		return productUrl;
	}

	public ProductStatus status() {
		return status;
	}

	public LocalDateTime discoveredAt() {
		return discoveredAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof DiscoveredProduct d)) {
			return false;
		}
		return Objects.equals(id, d.id) && Objects.equals(productUrl, d.productUrl);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, productUrl);
	}

	@Override
	public String toString() {
		return "DiscoveredProduct{brand='%s', name='%s', category=%s, lounge=CHF %s}".formatted(brand, name, category,
				loungePrice);
	}

}
