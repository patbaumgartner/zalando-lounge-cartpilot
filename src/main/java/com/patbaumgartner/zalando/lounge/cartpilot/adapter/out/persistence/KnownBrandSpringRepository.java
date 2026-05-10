package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface KnownBrandSpringRepository extends CrudRepository<KnownBrandJdbcEntity, Long> {

	@Query("SELECT * FROM known_brands WHERE brand_name = :brandName")
	Optional<KnownBrandJdbcEntity> findByBrandName(@Param("brandName") String brandName);

}
