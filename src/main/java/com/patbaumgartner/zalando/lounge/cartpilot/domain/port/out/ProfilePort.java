package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;

import java.util.List;
import java.util.Optional;

/** Repository port for {@link Profile} aggregates. */
public interface ProfilePort {

	List<Profile> findAllActive();

	List<Profile> findAll();

	Optional<Profile> findById(Long id);

	Optional<Profile> findByName(String name);

	Profile save(Profile profile);

}
