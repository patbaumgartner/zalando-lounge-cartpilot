package com.patbaumgartner.zalando.lounge.cartpilot;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit tests enforcing the Hexagonal Architecture rules:
 *
 * - domain/ has ZERO dependencies on Spring, adapters, or application layer - adapter/
 * depends on domain and application only (never on other adapters) - application/ depends
 * only on domain - config/ may depend on anything (it is the wiring layer)
 */
@DisplayName("Hexagonal architecture rules")
class HexagonalArchitectureTest {

	private static JavaClasses classes;

	@BeforeAll
	static void importClasses() {
		classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.patbaumgartner.zalando.lounge.cartpilot");
	}

	@Test
	@DisplayName("domain layer has no dependency on Spring Framework")
	void domainHasNoDependencyOnSpring() {
		ArchRule rule = noClasses().that()
			.resideInAPackage("..domain..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("org.springframework..");

		rule.check(classes);
	}

	@Test
	@DisplayName("domain layer has no dependency on adapters")
	void domainDoesNotDependOnAdapters() {
		ArchRule rule = noClasses().that()
			.resideInAPackage("..domain..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("..adapter..");

		rule.check(classes);
	}

	@Test
	@DisplayName("domain layer has no dependency on application layer")
	void domainDoesNotDependOnApplication() {
		ArchRule rule = noClasses().that()
			.resideInAPackage("..domain..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("..application..");

		rule.check(classes);
	}

	@Test
	@DisplayName("application layer depends only on domain — not on adapters")
	void applicationDoesNotDependOnAdapters() {
		ArchRule rule = noClasses().that()
			.resideInAPackage("..application..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("..adapter..");

		rule.check(classes);
	}

	@Test
	@DisplayName("outbound adapters do not call each other directly")
	void outboundAdaptersDoNotCrossReference() {
		ArchRule rule = noClasses().that()
			.resideInAPackage("..adapter.out.persistence..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("..adapter.out.browser..")
			.orShould()
			.dependOnClassesThat()
			.resideInAPackage("..adapter.out.telegram..");

		rule.check(classes);
	}

	@Test
	@DisplayName("inbound adapters do not access persistence directly")
	void inboundAdaptersDoNotAccessPersistenceDirectly() {
		ArchRule rule = noClasses().that()
			.resideInAPackage("..adapter.in..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("..adapter.out.persistence..");

		rule.check(classes);
	}

	@Test
	@DisplayName("domain ports are interfaces")
	void domainPortsAreInterfaces() {
		ArchRule rule = classes().that()
			.resideInAPackage("..domain.port..")
			.and()
			.areTopLevelClasses()
			.should()
			.beInterfaces();

		rule.check(classes);
	}

	@Test
	@DisplayName("layered architecture is respected")
	void layeredArchitectureIsRespected() {
		var rule = layeredArchitecture().consideringOnlyDependenciesInLayers()
			.layer("Domain")
			.definedBy("com.patbaumgartner.zalando.lounge.cartpilot.domain..")
			.layer("Application")
			.definedBy("com.patbaumgartner.zalando.lounge.cartpilot.application..")
			.layer("Adapters")
			.definedBy("com.patbaumgartner.zalando.lounge.cartpilot.adapter..")
			.layer("Config")
			.definedBy("com.patbaumgartner.zalando.lounge.cartpilot.config..")

			.whereLayer("Domain")
			.mayNotAccessAnyLayer()
			.whereLayer("Application")
			.mayOnlyAccessLayers("Domain", "Config")
			.whereLayer("Adapters")
			.mayOnlyAccessLayers("Domain", "Application", "Config")
			.whereLayer("Config")
			.mayOnlyAccessLayers("Domain", "Application");

		rule.check(classes);
	}

}
