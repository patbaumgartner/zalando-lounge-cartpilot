package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface ProfileSpringRepository extends CrudRepository<ProfileJdbcEntity, Long> {

	@Query("SELECT * FROM profiles WHERE active = TRUE")
	List<ProfileJdbcEntity> findAllActive();

	@Query("SELECT * FROM profiles WHERE name = :name")
	Optional<ProfileJdbcEntity> findByName(@Param("name") String name);

}
