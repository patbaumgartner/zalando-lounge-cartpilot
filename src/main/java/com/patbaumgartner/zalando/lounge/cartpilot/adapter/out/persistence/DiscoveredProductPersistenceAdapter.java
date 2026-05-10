package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.DiscoveredProductPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Component
class DiscoveredProductPersistenceAdapter implements DiscoveredProductPort {

	private final DiscoveredProductSpringRepository repository;

	DiscoveredProductPersistenceAdapter(DiscoveredProductSpringRepository repository) {
		this.repository = repository;
	}

	@Override
	public DiscoveredProduct save(DiscoveredProduct product) {
		return toDomain(repository.save(toEntity(product)));
	}

	@Override
	public List<DiscoveredProduct> saveAll(List<DiscoveredProduct> products) {
		var entities = products.stream().map(this::toEntity).toList();
		var saved = repository.saveAll(entities);
		return ((Iterable<DiscoveredProductJdbcEntity>) saved).iterator().hasNext()
				? StreamSupport.stream(saved.spliterator(), false).map(this::toDomain).toList() : List.of();
	}

	@Override
	public Optional<DiscoveredProduct> findById(Long id) {
		return repository.findById(id).map(this::toDomain);
	}

	@Override
	public List<DiscoveredProduct> findByDiscoveredAt(LocalDate date) {
		return repository.findByDiscoveredAtBetween(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
			.stream()
			.map(this::toDomain)
			.toList();
	}

	@Override
	public void updateStatus(Long productId, ProductStatus status) {
		repository.findById(productId).ifPresent(e -> {
			e.status = status.name();
			repository.save(e);
		});
	}

	// ── Mapping ────────────────────────────────────────────────

	private DiscoveredProduct toDomain(DiscoveredProductJdbcEntity e) {
		return new DiscoveredProduct(e.id, e.campaignId, e.brand, e.name, Category.fromString(e.category),
				Gender.valueOf(e.gender), splitCsv(e.sizesAvailable), e.originalPrice, e.loungePrice, e.discountPct,
				e.productUrl, ProductStatus.valueOf(e.status), e.discoveredAt);
	}

	private DiscoveredProductJdbcEntity toEntity(DiscoveredProduct d) {
		var e = new DiscoveredProductJdbcEntity();
		e.id = d.id();
		e.campaignId = d.campaignId();
		e.brand = d.brand();
		e.name = d.name();
		e.category = d.category().name();
		e.gender = d.gender().name();
		e.sizesAvailable = String.join(",", d.sizesAvailable());
		e.originalPrice = d.originalPrice();
		e.loungePrice = d.loungePrice();
		e.discountPct = d.discountPct();
		e.productUrl = d.productUrl();
		e.status = d.status().name();
		e.discoveredAt = d.discoveredAt() != null ? d.discoveredAt() : LocalDateTime.now();
		return e;
	}

	private List<String> splitCsv(String csv) {
		if (csv == null || csv.isBlank()) {
			return List.of();
		}
		return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

}
