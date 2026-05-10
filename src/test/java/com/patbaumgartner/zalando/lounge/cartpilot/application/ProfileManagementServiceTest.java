package com.patbaumgartner.zalando.lounge.cartpilot.application;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.BrandTier;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import com.patbaumgartner.zalando.lounge.cartpilot.testdata.ProfileTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileManagementService")
class ProfileManagementServiceTest {

	@Mock
	private ProfilePort profilePort;

	private ProfileManagementService service;

	@BeforeEach
	void setUp() {
		service = new ProfileManagementService(profilePort);
	}

	@Nested
	@DisplayName("show")
	class Show {

		@Test
		@DisplayName("returns profile when found")
		void returnsProfileWhenFound() {
			var profile = ProfileTestData.pat();
			when(profilePort.findByName("Pat")).thenReturn(Optional.of(profile));

			assertThat(service.show("Pat")).isEqualTo(profile);
		}

		@Test
		@DisplayName("throws ProfileNotFoundException when not found")
		void throwsWhenNotFound() {
			when(profilePort.findByName("Ghost")).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.show("Ghost"))
				.isInstanceOf(ProfileManagementService.ProfileNotFoundException.class)
				.hasMessageContaining("Ghost");
		}

	}

	@Nested
	@DisplayName("activate / deactivate")
	class ActivateDeactivate {

		@Test
		@DisplayName("activates an inactive profile")
		void activatesProfile() {
			var profile = ProfileTestData.aProfile().inactive().build();
			when(profilePort.findByName("Pat")).thenReturn(Optional.of(profile));
			when(profilePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			service.activate("Pat");

			assertThat(profile.active()).isTrue();
			verify(profilePort).save(profile);
		}

		@Test
		@DisplayName("deactivates an active profile")
		void deactivatesProfile() {
			var profile = ProfileTestData.aProfile().build();
			when(profilePort.findByName("Pat")).thenReturn(Optional.of(profile));
			when(profilePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			service.deactivate("Pat");

			assertThat(profile.active()).isFalse();
			verify(profilePort).save(profile);
		}

	}

	@Nested
	@DisplayName("setSize")
	class SetSize {

		@Test
		@DisplayName("persists profile with updated size")
		void updatesSize() {
			var profile = ProfileTestData.pat();
			when(profilePort.findByName("Pat")).thenReturn(Optional.of(profile));
			when(profilePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			service.setSize("Pat", Category.SHOES, "44");

			verify(profilePort).save(argThat(p -> p.sizeFor(Category.SHOES).filter("44"::equals).isPresent()));
		}

	}

	@Nested
	@DisplayName("setMaxPrice")
	class SetMaxPrice {

		@Test
		@DisplayName("persists profile with updated max price")
		void updatesMaxPrice() {
			var profile = ProfileTestData.pat();
			when(profilePort.findByName("Pat")).thenReturn(Optional.of(profile));
			when(profilePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			service.setMaxPrice("Pat", Category.JACKETS, new BigDecimal("350"));

			verify(profilePort).save(argThat(p -> p.maxPriceFor(Category.JACKETS)
				.map(mp -> mp.compareTo(new BigDecimal("350")) == 0)
				.orElse(false)));
		}

	}

	@Nested
	@DisplayName("addBrand / removeBrand")
	class BrandManagement {

		@Test
		@DisplayName("adds brand to Tier 1")
		void addsBrandToTier1() {
			var profile = ProfileTestData.pat();
			when(profilePort.findByName("Pat")).thenReturn(Optional.of(profile));
			when(profilePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			service.addBrand("Pat", BrandTier.TIER_1, "Patagonia");

			verify(profilePort).save(argThat(p -> p.brandTier1().contains("Patagonia")));
		}

		@Test
		@DisplayName("removes brand from profile")
		void removesBrand() {
			var profile = ProfileTestData.aProfile().withTier1Brand("Mammut").build();
			when(profilePort.findByName("Pat")).thenReturn(Optional.of(profile));
			when(profilePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

			service.removeBrand("Pat", "Mammut");

			verify(profilePort).save(argThat(p -> !p.brandTier1().contains("Mammut")));
		}

	}

	@Test
	@DisplayName("listAll delegates to port")
	void listAllDelegatesToPort() {
		var profiles = List.of(ProfileTestData.pat());
		when(profilePort.findAll()).thenReturn(profiles);

		assertThat(service.listAll()).isEqualTo(profiles);
	}

}
