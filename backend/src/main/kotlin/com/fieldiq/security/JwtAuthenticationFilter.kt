package com.fieldiq.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT authentication filter that validates bearer tokens on incoming requests.
 *
 * Runs once per request via [OncePerRequestFilter] and integrates with Spring Security.
 * For requests with a valid JWT in the Authorization header, it sets a
 * [UsernamePasswordAuthenticationToken] in the [SecurityContextHolder] with the
 * user UUID as the principal.
 *
 * Skipped paths: auth, actuator, and cross-instance relay endpoints use either
 * no auth or HMAC-based auth.
 *
 * If the token is missing, expired, or invalid, the filter continues the chain
 * without setting the SecurityContext. Spring Security will return 401 for
 * protected endpoints.
 *
 * @property jwtService The service that validates JWT tokens and extracts user IDs.
 * @see SecurityConfig for endpoint authorization rules.
 * @see JwtService for token validation logic.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }

    /**
     * Determines whether this filter should be skipped for the given request.
     * Public endpoints (auth, actuator, cross-instance relay) skip JWT validation.
     *
     * @param request The incoming HTTP request.
     * @return True if the request path should skip JWT validation.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path.startsWith("/auth/") ||
            path.startsWith("/actuator/") ||
            path.startsWith("/api/negotiate/")
    }

    /**
     * Extracts the JWT from the Authorization header, validates it, and sets
     * the SecurityContext if valid. The principal is the user UUID.
     *
     * @param request The incoming HTTP request.
     * @param response The HTTP response (not modified by this filter).
     * @param filterChain The remaining filter chain to invoke.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authHeader = request.getHeader(AUTHORIZATION_HEADER)

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            val token = authHeader.substring(BEARER_PREFIX.length)
            val userId = jwtService.validateAccessToken(token)

            if (userId != null) {
                val authentication = UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    emptyList()
                )
                SecurityContextHolder.getContext().authentication = authentication
            }
        }

        filterChain.doFilter(request, response)
    }
}
