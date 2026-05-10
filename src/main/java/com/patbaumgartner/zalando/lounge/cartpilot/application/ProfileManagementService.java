package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Handles profile CRUD driven by Telegram admin commands (UC-07).
 */
@Service
public class ProfileManagementService {

	private static final Logger log = LoggerFactory.getLogger(ProfileManagementService.class);

	private final ProfilePort profilePort;

	public ProfileManagementService(ProfilePort profilePort) {
		this.profilePort = profilePort;
	}

	public List<Profile> listAll() {
		return profilePort.findAll();
	}

	public Profile show(String name) {
		return profilePort.findByName(name).orElseThrow(() -> new ProfileNotFoundException(name));
	}

	public void activate(String name) {
		var profile = show(name);
		profile.activate();
		profilePort.save(profile);
		log.info("Profile {} activated", name);
	}

	public void deactivate(String name) {
		var profile = show(name);
		profile.deactivate();
		profilePort.save(profile);
		log.info("Profile {} deactivated", name);
	}

	public void setSize(String profileName, Category category, String size) {
		var profile = show(profileName);
		profilePort.save(profile.withSize(category, size));
		log.info("Profile {}: size {} = {}", profileName, category, size);
	}

	public void setMaxPrice(String profileName, Category category, BigDecimal price) {
		var profile = show(profileName);
		profilePort.save(profile.withMaxPrice(category, price));
		log.info("Profile {}: max price for {} = CHF {}", profileName, category, price);
	}

	public void addBrand(String profileName, BrandTier tier, String brand) {
		var profile = show(profileName);
		profilePort.save(profile.withBrandInTier(tier, brand));
		log.info("Profile {}: added {} to {}", profileName, brand, tier);
	}

	public void removeBrand(String profileName, String brand) {
		var profile = show(profileName);
		profilePort.save(profile.withBrandRemoved(brand));
		log.info("Profile {}: removed brand {}", profileName, brand);
	}

	// ── Exception ──────────────────────────────────────────────

	public static class ProfileNotFoundException extends RuntimeException {

		public ProfileNotFoundException(String name) {
			super("Profile not found: " + name);
		}

	}

}
