package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface PurchasedItemSpringRepository extends CrudRepository<PurchasedItemJdbcEntity, Long> {

	@Query("SELECT COUNT(*) > 0 FROM purchased_items WHERE profile_id = :profileId AND product_id = :productId")
	boolean existsByProfileIdAndProductId(@Param("profileId") Long profileId, @Param("productId") Long productId);

	@Query("SELECT DISTINCT dp.product_url FROM purchased_items pi "
			+ "JOIN discovered_products dp ON dp.id = pi.product_id WHERE pi.profile_id = :profileId")
	List<String> findPurchasedProductUrlsByProfileId(@Param("profileId") Long profileId);

}
