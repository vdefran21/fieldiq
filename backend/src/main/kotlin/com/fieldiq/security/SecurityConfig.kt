package com.fieldiq.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Spring Security configuration for the FieldIQ backend.
 *
 * Configures stateless, JWT-based authentication with three access tiers:
 *
 * 1. **Public endpoints** (auth routes, actuator health): No authentication required.
 *    These handle OTP login, token refresh, and health checks.
 *
 * 2. **Cross-instance relay endpoints** (negotiate relay routes): Exempt from JWT auth
 *    because they use HMAC-SHA256 signature authentication instead. The HMAC validation
 *    is handled by a dedicated filter/interceptor (not Spring Security), since the
 *    auth model is fundamentally different (per-session derived keys vs. user JWTs).
 *
 * 3. **All other endpoints**: Require a valid JWT in the `Authorization: Bearer` header.
 *    JWT validation is handled by [JwtAuthenticationFilter], which extracts the user ID
 *    from the token and sets the SecurityContext.
 *
 * **Why CSRF is disabled:** FieldIQ is a pure API server consumed by a mobile app.
 * There are no browser-based form submissions, so CSRF protection adds complexity
 * without security benefit. All state-changing operations require a valid JWT.
 *
 * **Why sessions are stateless:** JWTs are self-contained; the server doesn't need
 * to store session state. This simplifies horizontal scaling (any instance can
 * validate any token) and aligns with the two-instance local dev setup.
 *
 * @property jwtAuthenticationFilter The JWT filter that validates bearer tokens and
 *   sets the SecurityContext. Injected by Spring and registered before the default
 *   [UsernamePasswordAuthenticationFilter].
 * @see JwtAuthenticationFilter for token extraction and validation.
 * @see JwtService for JWT generation and parsing.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
) {

    /**
     * Builds the security filter chain with endpoint authorization rules.
     *
     * Registers [JwtAuthenticationFilter] before Spring's default username/password
     * filter so that JWT validation runs on every authenticated request. The JWT
     * filter itself skips public endpoints (see [JwtAuthenticationFilter.shouldNotFilter]).
     *
     * @param http The [HttpSecurity] builder provided by Spring Security.
     * @return The configured [SecurityFilterChain].
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // Public endpoints
                    .requestMatchers("/auth/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    // Cross-instance relay endpoints (HMAC-authenticated, not JWT)
                    .requestMatchers("/api/negotiate/**").permitAll()
                    // Everything else requires authentication
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
