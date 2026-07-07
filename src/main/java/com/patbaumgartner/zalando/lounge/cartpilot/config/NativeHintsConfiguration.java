package com.patbaumgartner.zalando.lounge.cartpilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Central registration of GraalVM native-image hints for the application.
 * <p>
 * Imports {@link PlaywrightRuntimeHints} (reflection and driver resources for Microsoft
 * Playwright — without them the native image fails with
 * {@code ClassNotFoundException: com.microsoft.playwright.impl.driver.jar.DriverJar} when
 * the {@code playwright} bean calls {@link com.microsoft.playwright.Playwright#create()})
 * and {@link TelegramBotsRuntimeHints} (reflection for the Telegram Bot API types the
 * telegrambots library (de)serialises with Jackson).
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints({ PlaywrightRuntimeHints.class, TelegramBotsRuntimeHints.class })
public class NativeHintsConfiguration {

}
