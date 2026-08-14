package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.PurchasedItem;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.PurchasedItemPort;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
class PurchasedItemPersistenceAdapter implements PurchasedItemPort {

	private final PurchasedItemSpringRepository repository;

	PurchasedItemPersistenceAdapter(PurchasedItemSpringRepository repository) {
		this.repository = repository;
	}

	@Override
	public boolean hasProfilePurchasedProduct(Long profileId, Long productId) {
		return repository.existsByProfileIdAndProductId(profileId, productId);
	}

	@Override
	public Set<String> findPurchasedArticleKeysByProfileId(Long profileId) {
		return repository.findPurchasedProductUrlsByProfileId(profileId)
			.stream()
			.map(DiscoveredProduct::articleKeyOf)
			.filter(key -> !key.isBlank())
			.collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public PurchasedItem save(PurchasedItem item) {
		return toDomain(repository.save(toEntity(item)));
	}

	// ── Mapping ────────────────────────────────────────────────

	private PurchasedItem toDomain(PurchasedItemJdbcEntity e) {
		return new PurchasedItem(e.id, e.profileId, e.productId, e.purchasedAt, e.purchasedByUsername);
	}

	private PurchasedItemJdbcEntity toEntity(PurchasedItem item) {
		var e = new PurchasedItemJdbcEntity();
		e.id = item.id();
		e.profileId = item.profileId();
		e.productId = item.productId();
		e.purchasedAt = item.purchasedAt();
		e.purchasedByUsername = item.purchasedByUsername();
		return e;
	}

}
