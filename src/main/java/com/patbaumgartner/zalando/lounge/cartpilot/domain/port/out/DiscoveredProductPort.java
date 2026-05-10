package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.DiscoveredProduct;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Repository port for discovered campaign products. */
public interface DiscoveredProductPort {

	DiscoveredProduct save(DiscoveredProduct product);

	List<DiscoveredProduct> saveAll(List<DiscoveredProduct> products);

	Optional<DiscoveredProduct> findById(Long id);

	List<DiscoveredProduct> findByDiscoveredAt(LocalDate date);

	void updateStatus(Long productId, ProductStatus status);

}
