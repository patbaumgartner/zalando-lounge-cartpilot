package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface PurchasedItemSpringRepository extends CrudRepository<PurchasedItemJdbcEntity, Long> {

	@Query("SELECT COUNT(*) > 0 FROM purchased_items WHERE profile_id = :profileId AND product_id = :productId")
	boolean existsByProfileIdAndProductId(@Param("profileId") Long profileId, @Param("productId") Long productId);

	@Query("SELECT product_id FROM purchased_items WHERE profile_id = :profileId")
	List<Long> findProductIdsByProfileId(@Param("profileId") Long profileId);

}
