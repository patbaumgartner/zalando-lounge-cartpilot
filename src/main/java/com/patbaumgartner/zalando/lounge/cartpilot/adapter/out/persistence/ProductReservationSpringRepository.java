package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

interface ProductReservationSpringRepository extends CrudRepository<ProductReservationJdbcEntity, Long> {

	@Query("SELECT * FROM product_reservations WHERE status = :status")
	List<ProductReservationJdbcEntity> findByStatus(@Param("status") String status);

	@Query("SELECT * FROM product_reservations WHERE profile_id = :profileId")
	List<ProductReservationJdbcEntity> findByProfileId(@Param("profileId") Long profileId);

	@Query("SELECT * FROM product_reservations WHERE status = :status AND created_at >= :from AND created_at < :to")
	List<ProductReservationJdbcEntity> findByStatusAndCreatedAtBetween(@Param("status") String status,
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	@Query("SELECT status, COUNT(*) AS total FROM product_reservations GROUP BY status")
	List<StatusCount> countGroupedByStatus();

	record StatusCount(String status, long total) {
	}

}
