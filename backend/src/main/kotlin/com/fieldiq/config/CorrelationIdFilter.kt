package com.fieldiq.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Adds a stable request correlation ID to every HTTP request and response.
 *
 * Phase 1's beta hardening needs a low-friction way to correlate API errors, relay calls,
 * and agent queue handoffs. This filter reuses a caller-supplied `X-Request-Id` when present
 * or generates one locally, stores it in SLF4J's MDC for downstream logs, and mirrors it
 * back on the response headers.
 */
@Component
class CorrelationIdFilter : OncePerRequestFilter() {

    companion object {
        private const val HEADER_NAME = "X-Request-Id"
        private const val MDC_KEY = "requestId"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER_NAME)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, requestId)
        response.setHeader(HEADER_NAME, requestId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }
}
