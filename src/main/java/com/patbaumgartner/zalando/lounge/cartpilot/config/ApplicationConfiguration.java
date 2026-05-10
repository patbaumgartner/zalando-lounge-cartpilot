package com.patbaumgartner.zalando.lounge.cartpilot.config;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.service.BrandMatcher;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.service.ProductFilter;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.service.ProductScorer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CartPilotProperties.class)
public class ApplicationConfiguration {

	@Bean
	BrandMatcher brandMatcher() {
		return new BrandMatcher();
	}

	@Bean
	ProductScorer productScorer() {
		return new ProductScorer();
	}

	@Bean
	ProductFilter productFilter(BrandMatcher brandMatcher, ProductScorer productScorer) {
		return new ProductFilter(brandMatcher, productScorer);
	}

}
