package com.fieldiq.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Root application configuration that activates type-safe property binding.
 *
 * This class enables [FieldIQProperties] so that all `fieldiq.*` YAML properties
 * are bound to typed Kotlin data classes at startup. Additional `@Bean` definitions
 * for cross-cutting concerns (e.g., Jackson ObjectMapper customization, CORS config)
 * should be added here as the application grows.
 *
 * @see FieldIQProperties for the bound configuration properties.
 */
@Configuration
@EnableConfigurationProperties(FieldIQProperties::class)
class AppConfig
