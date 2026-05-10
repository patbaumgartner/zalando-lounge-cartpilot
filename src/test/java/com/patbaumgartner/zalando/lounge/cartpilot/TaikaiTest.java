package com.patbaumgartner.zalando.lounge.cartpilot;

import com.enofex.taikai.Taikai;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaModifier.FINAL;
import static com.tngtech.archunit.core.domain.JavaModifier.PRIVATE;

@DisplayName("Taikai coding conventions")
class TaikaiTest {

	@Test
	@DisplayName("codebase fulfils all defined coding constraints")
	void shouldFulfillConstraints() {
		Taikai.builder()
			.namespace("com.patbaumgartner.zalando.lounge.cartpilot")
			.java(java -> java.noUsageOfDeprecatedAPIs()
				.noUsageOfSystemOutOrErr()
				.methodsShouldNotDeclareGenericExceptions()
				.utilityClassesShouldBeFinalAndHavePrivateConstructor()
				.finalClassesShouldNotHaveProtectedMembers()
				.imports(imports -> imports.shouldHaveNoCycles().shouldNotImport("java.util.logging.."))
				.naming(naming -> naming.classesShouldNotMatch(".*Impl")
					.methodsShouldNotMatch("^(foo$|bar$).*")
					.fieldsShouldNotMatch(".*(List|Set|Map)$")
					.packagesShouldMatchDefault()
					.interfacesShouldNotHavePrefixI()))
			.logging(logging -> logging.loggersShouldFollowConventions(Logger.class, "log", List.of(PRIVATE, FINAL)))
			.test(test -> test.junit(junit -> junit.classesShouldNotBeAnnotatedWithDisabled()
				.methodsShouldNotBeAnnotatedWithDisabled()
				.classesShouldBePackagePrivate(".*Test")
				.methodsShouldBePackagePrivate()
				.methodsShouldBeAnnotatedWithDisplayName()))
			.spring(spring -> spring.noAutowiredFields()
				.boot(boot -> boot.applicationClassShouldResideInPackage("com.patbaumgartner.zalando.lounge.cartpilot"))
				.configurations(configuration -> configuration.namesShouldEndWithConfiguration())
				.properties(properties -> properties.shouldBeRecords().namesShouldEndWithProperties())
				.controllers(controllers -> controllers.shouldBeAnnotatedWithRestController()
					.namesShouldEndWithController()
					.shouldNotDependOnOtherControllers()
					.shouldBePackagePrivate())
				.services(services -> services.shouldBeAnnotatedWithService()
					.namesShouldEndWithService()
					.shouldNotDependOnControllers())
				.repositories(repositories -> repositories.namesShouldEndWithRepository()))
			.build()
			.checkAll();
	}

}
