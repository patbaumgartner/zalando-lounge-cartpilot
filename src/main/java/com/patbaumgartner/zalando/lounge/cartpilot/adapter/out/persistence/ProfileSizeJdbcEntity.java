package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("profile_sizes")
class ProfileSizeJdbcEntity {

	@Id
	Long id;

	Long profileId;

	String category;

	String size;

}
