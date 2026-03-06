package com.fieldiq.security

import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Extracts the authenticated user's UUID from the Spring Security context.
 *
 * After [JwtAuthenticationFilter] validates a JWT and sets the SecurityContext,
 * controllers call this function to retrieve the user ID. The user ID is stored
 * as the principal in the [org.springframework.security.authentication.UsernamePasswordAuthenticationToken].
 *
 * **Usage in controllers:**
 * ```kotlin
 * @GetMapping("/teams/{teamId}/events")
 * fun getEvents(@PathVariable teamId: UUID): List<EventDto> {
 *     val userId = authenticatedUserId()
 *     return eventService.getTeamEvents(userId, teamId)
 * }
 * ```
 *
 * @return The UUID of the authenticated user.
 * @throws IllegalStateException If called outside an authenticated context (e.g., on
 *   a public endpoint where no JWT was provided). This should never happen if
 *   [SecurityConfig] authorization rules are correctly configured.
 */
fun authenticatedUserId(): UUID {
    val principal = SecurityContextHolder.getContext().authentication?.principal
        ?: throw IllegalStateException("No authenticated user in SecurityContext")
    return when (principal) {
        is UUID -> principal
        is String -> UUID.fromString(principal)
        else -> throw IllegalStateException("Unexpected principal type: ${principal::class.simpleName}")
    }
}
