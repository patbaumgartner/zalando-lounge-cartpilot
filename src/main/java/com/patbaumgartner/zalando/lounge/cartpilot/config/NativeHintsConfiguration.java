package com.patbaumgartner.zalando.lounge.cartpilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Central registration of GraalVM native-image hints for the application.
 * <p>
 * Imports {@link PlaywrightRuntimeHints} (reflection and driver resources for Microsoft
 * Playwright). Without these hints the native image fails at startup with
 * {@code ClassNotFoundException: com.microsoft.playwright.impl.driver.jar.DriverJar} when
 * the {@code playwright} bean calls {@link com.microsoft.playwright.Playwright#create()}.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints({ PlaywrightRuntimeHints.class })
public class NativeHintsConfiguration {

}
