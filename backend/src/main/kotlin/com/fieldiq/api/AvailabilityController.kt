package com.fieldiq.api

import com.fieldiq.api.dto.AvailabilityWindowDto
import com.fieldiq.api.dto.CreateAvailabilityWindowRequest
import com.fieldiq.security.authenticatedUserId
import com.fieldiq.service.AvailabilityWindowService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for availability window management.
 *
 * Provides CRUD operations for user availability declarations. Parents and coaches
 * use these endpoints to declare when they are available or unavailable, which feeds
 * into the [com.fieldiq.service.SchedulingService] for optimal game time computation.
 *
 * Endpoints are split across two URL patterns:
 * - `/users/me/availability` — Current user's availability (create, list, delete)
 * - `/teams/{teamId}/availability` — Team-wide availability view
 *
 * All endpoints require JWT authentication.
 *
 * @property availabilityWindowService Business logic for availability window operations.
 * @see AvailabilityWindowService for the underlying business logic.
 * @see com.fieldiq.service.TeamAccessGuard for multi-tenancy enforcement.
 */
@RestController
class AvailabilityController(
    private val availabilityWindowService: AvailabilityWindowService,
) {

    /**
     * Creates a new availability window for the authenticated user.
     *
     * Validates that exactly one of dayOfWeek/specificDate is provided and that
     * startTime is before endTime. The user must be an active member of the
     * specified team.
     *
     * @param request The window details: team, day/date, start/end times, type.
     * @return 201 Created with the [AvailabilityWindowDto] of the new window.
     */
    @PostMapping("/users/me/availability")
    fun createWindow(
        @Valid @RequestBody request: CreateAvailabilityWindowRequest,
    ): ResponseEntity<AvailabilityWindowDto> {
        val userId = authenticatedUserId()
        val window = availabilityWindowService.createWindow(userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(window)
    }

    /**
     * Lists all availability windows for the authenticated user across all teams.
     *
     * Returns windows of all types ("available" and "unavailable") from all
     * teams the user belongs to.
     *
     * @return 200 OK with a list of [AvailabilityWindowDto].
     */
    @GetMapping("/users/me/availability")
    fun getUserAvailability(): ResponseEntity<List<AvailabilityWindowDto>> {
        val userId = authenticatedUserId()
        val windows = availabilityWindowService.getUserAvailability(userId)
        return ResponseEntity.ok(windows)
    }

    /**
     * Lists all availability windows for a team across all members.
     *
     * Used by managers to view team-wide availability and by the scheduling engine.
     * Requires the authenticated user to be an active team member.
     *
     * @param teamId The UUID of the team whose availability to list.
     * @return 200 OK with a list of [AvailabilityWindowDto] for all team members.
     */
    @GetMapping("/teams/{teamId}/availability")
    fun getTeamAvailability(@PathVariable teamId: UUID): ResponseEntity<List<AvailabilityWindowDto>> {
        val userId = authenticatedUserId()
        val windows = availabilityWindowService.getTeamAvailability(userId, teamId)
        return ResponseEntity.ok(windows)
    }

    /**
     * Deletes an availability window owned by the authenticated user.
     *
     * Only the user who created the window can delete it — managers cannot delete
     * other members' availability. Returns 404 if the window doesn't exist, 403
     * if it belongs to another user.
     *
     * @param windowId The UUID of the availability window to delete.
     * @return 204 No Content on successful deletion.
     */
    @DeleteMapping("/users/me/availability/{windowId}")
    fun deleteWindow(@PathVariable windowId: UUID): ResponseEntity<Void> {
        val userId = authenticatedUserId()
        availabilityWindowService.deleteWindow(userId, windowId)
        return ResponseEntity.noContent().build()
    }
}
