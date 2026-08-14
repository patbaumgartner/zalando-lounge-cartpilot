package com.patbaumgartner.zalando.lounge.cartpilot.testdata;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Test data builder (Object Mother) for {@link DiscoveredProduct}.
 */
public class ProductTestData {

	private Long id = 1L;

	private String campaignId = "campaign-001";

	private String brand = "Mammut";

	private String name = "Convey Tour 45";

	private Category category = Category.JACKETS;

	private Gender gender = Gender.MEN;

	private List<String> sizesAvailable = new ArrayList<>(List.of("S", "M", "L", "XL", "50", "52"));

	private BigDecimal originalPrice = new BigDecimal("310");

	private BigDecimal loungePrice = new BigDecimal("189");

	private int discountPct = 39;

	private String productUrl = "https://www.zalando-lounge.ch/campaigns/camp-1/articles/MA345F0AB-K11";

	private ProductStatus status = ProductStatus.DISCOVERED;

	private final LocalDateTime discoveredAt = LocalDateTime.now();

	private ProductTestData() {
	}

	public static ProductTestData aProduct() {
		return new ProductTestData();
	}

	public ProductTestData withProductUrl(String productUrl) {
		this.productUrl = productUrl;
		return this;
	}

	public static DiscoveredProduct mammutJacket() {
		return aProduct().withBrand("Mammut")
			.withName("Convey Tour 45")
			.withCategory(Category.JACKETS)
			.withGender(Gender.MEN)
			.withSizesAvailable(List.of("48", "50", "52", "54"))
			.withOriginalPrice(new BigDecimal("310"))
			.withLoungePrice(new BigDecimal("189"))
			.withDiscountPct(39)
			.build();
	}

	public static DiscoveredProduct jackWolfskinFleece() {
		return aProduct().withId(2L)
			.withBrand("Jack Wolfskin")
			.withName("Moonrise Fleece")
			.withCategory(Category.JACKETS)
			.withGender(Gender.MEN)
			.withSizesAvailable(List.of("M", "L", "XL", "50", "52"))
			.withOriginalPrice(new BigDecimal("130"))
			.withLoungePrice(new BigDecimal("79"))
			.withDiscountPct(39)
			.build();
	}

	public ProductTestData withId(Long id) {
		this.id = id;
		return this;
	}

	public ProductTestData withCampaignId(String campaignId) {
		this.campaignId = campaignId;
		return this;
	}

	public ProductTestData withBrand(String brand) {
		this.brand = brand;
		return this;
	}

	public ProductTestData withName(String name) {
		this.name = name;
		return this;
	}

	public ProductTestData withCategory(Category category) {
		this.category = category;
		return this;
	}

	public ProductTestData withGender(Gender gender) {
		this.gender = gender;
		return this;
	}

	public ProductTestData withSizesAvailable(List<String> sizes) {
		this.sizesAvailable = new ArrayList<>(sizes);
		return this;
	}

	public ProductTestData withOriginalPrice(BigDecimal price) {
		this.originalPrice = price;
		return this;
	}

	public ProductTestData withLoungePrice(BigDecimal price) {
		this.loungePrice = price;
		return this;
	}

	public ProductTestData withDiscountPct(int pct) {
		this.discountPct = pct;
		return this;
	}

	public ProductTestData withStatus(ProductStatus status) {
		this.status = status;
		return this;
	}

	public DiscoveredProduct build() {
		return new DiscoveredProduct(id, campaignId, brand, name, category, gender, sizesAvailable, originalPrice,
				loungePrice, discountPct, productUrl, status, discoveredAt);
	}

}
