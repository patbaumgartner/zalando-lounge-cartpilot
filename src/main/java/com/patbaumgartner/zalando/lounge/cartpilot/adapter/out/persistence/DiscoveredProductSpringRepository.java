package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

interface DiscoveredProductSpringRepository extends CrudRepository<DiscoveredProductJdbcEntity, Long> {

	@Query("SELECT * FROM discovered_products WHERE discovered_at >= :from AND discovered_at < :to")
	List<DiscoveredProductJdbcEntity> findByDiscoveredAtBetween(@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);

}
