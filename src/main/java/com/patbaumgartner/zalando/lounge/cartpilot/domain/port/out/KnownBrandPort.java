package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.KnownBrand;

import java.util.List;

/** Repository port for the brand catalogue. */
public interface KnownBrandPort {

	void upsert(String brandName);

	void upsertAll(List<String> brandNames);

	List<KnownBrand> findAll();

}
