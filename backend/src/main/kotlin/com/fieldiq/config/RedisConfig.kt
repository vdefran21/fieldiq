package com.fieldiq.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Redis configuration for FieldIQ rate limiting and caching.
 *
 * Provides a [StringRedisTemplate] bean used primarily by
 * [com.fieldiq.service.OtpRateLimitService] for real-time OTP rate limiting.
 * Redis is the first line of defense for rate limiting (low-latency checks),
 * while the database serves as the persistent audit trail.
 *
 * In local dev, Redis runs on port 6379 via Docker Compose.
 * In tests, Redis is mocked via MockK (test profile disables real Redis).
 *
 * @see com.fieldiq.service.OtpRateLimitService for the rate limiting logic.
 */
@Configuration
class RedisConfig {

    /**
     * Creates a [StringRedisTemplate] for string-based Redis operations.
     *
     * Uses the auto-configured [RedisConnectionFactory] from Spring Boot,
     * which reads the connection URL from `spring.data.redis.url` in YAML.
     *
     * @param connectionFactory The auto-configured Redis connection factory.
     * @return A [StringRedisTemplate] for rate limiting counter operations.
     */
    @Bean
    fun stringRedisTemplate(connectionFactory: RedisConnectionFactory): StringRedisTemplate {
        return StringRedisTemplate(connectionFactory)
    }
}
