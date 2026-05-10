package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.KnownBrand;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.KnownBrandPort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.StreamSupport;

@Component
class KnownBrandPersistenceAdapter implements KnownBrandPort {

	private final KnownBrandSpringRepository repository;

	KnownBrandPersistenceAdapter(KnownBrandSpringRepository repository) {
		this.repository = repository;
	}

	@Override
	public void upsert(String brandName) {
		if (repository.findByBrandName(brandName).isEmpty()) {
			var entity = new KnownBrandJdbcEntity();
			entity.brandName = brandName;
			entity.firstSeenAt = LocalDateTime.now();
			repository.save(entity);
		}
	}

	@Override
	public void upsertAll(List<String> brandNames) {
		brandNames.forEach(this::upsert);
	}

	@Override
	public List<KnownBrand> findAll() {
		return StreamSupport.stream(repository.findAll().spliterator(), false)
			.map(e -> new KnownBrand(e.id, e.brandName, e.firstSeenAt))
			.toList();
	}

}
