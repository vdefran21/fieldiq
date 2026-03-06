package com.fieldiq

import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.runApplication
import java.util.TimeZone

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
 * **JVM timezone:** The application sets the JVM default timezone to UTC at startup
 * via [PostConstruct] to ensure consistency with the Hibernate JDBC timezone setting
 * (`hibernate.jdbc.time_zone: UTC`). Without this, `LocalTime` values stored in
 * `TIME` columns would be shifted by the JVM's local timezone offset, potentially
 * violating database CHECK constraints (e.g., `start_time < end_time` can fail
 * when times wrap past midnight after timezone conversion).
 *
 * @see com.fieldiq.config.AppConfig for application configuration.
 * @see com.fieldiq.security.SecurityConfig for authentication setup.
 */
@SpringBootApplication(exclude = [RedisRepositoriesAutoConfiguration::class])
class FieldIQApplication {
    /**
     * Sets the JVM default timezone to UTC to match Hibernate's JDBC timezone.
     *
     * Prevents timezone offset from being applied to `LocalTime` ↔ PostgreSQL `TIME`
     * column mappings. Without this, a `LocalTime` of 20:00 on a JVM in EST (UTC-5)
     * would be stored as 01:00 UTC, which can violate time-ordering constraints.
     */
    @PostConstruct
    fun setUtcTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
}

fun main(args: Array<String>) {
    runApplication<FieldIQApplication>(*args)
}
