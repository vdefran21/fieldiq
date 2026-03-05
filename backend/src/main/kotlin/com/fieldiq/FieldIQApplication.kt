package com.fieldiq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.runApplication

/**
 * Entry point for the FieldIQ backend application.
 *
 * FieldIQ is a Kotlin Spring Boot API server that handles scheduling, cross-team
 * negotiation, authentication, and real-time WebSocket updates for youth sports
 * team management.
 *
 * **Redis auto-configuration note:** [RedisRepositoriesAutoConfiguration] is excluded
 * because Spring Data's repository scanning incorrectly tries to map JPA entities as
 * Redis repositories when both `spring-boot-starter-data-jpa` and
 * `spring-boot-starter-data-redis` are on the classpath. Redis is used only for
 * caching and rate limiting (via RedisTemplate), not as a Spring Data repository store.
 *
 * To run locally:
 * ```bash
 * # Instance A (port 8080)
 * SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun
 *
 * # Instance B (port 8081, separate database)
 * SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun
 * ```
 *
 * @see com.fieldiq.config.AppConfig for application configuration.
 * @see com.fieldiq.security.SecurityConfig for authentication setup.
 */
@SpringBootApplication(exclude = [RedisRepositoriesAutoConfiguration::class])
class FieldIQApplication

fun main(args: Array<String>) {
    runApplication<FieldIQApplication>(*args)
}
