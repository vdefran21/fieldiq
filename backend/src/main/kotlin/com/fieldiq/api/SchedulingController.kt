package com.fieldiq.api

import com.fieldiq.api.dto.SuggestWindowsRequest
import com.fieldiq.api.dto.TimeWindowDto
import com.fieldiq.security.authenticatedUserId
import com.fieldiq.service.SchedulingService
import com.fieldiq.service.TeamAccessGuard
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * REST controller for scheduling operations.
 *
 * Provides the `POST /teams/:teamId/suggest-windows` endpoint that computes
 * optimal meeting times based on team member availability. This is the entry
 * point for the deterministic scheduling algorithm — no LLM is involved.
 *
 * The endpoint requires the authenticated user to be an active member of the
 * specified team (enforced by [TeamAccessGuard]).
 *
 * @property schedulingService The deterministic scheduling engine.
 * @property teamAccessGuard Multi-tenancy guard for membership checks.
 * @see SchedulingService for the underlying algorithm.
 * @see SuggestWindowsRequest for the request body format.
 * @see TimeWindowDto for the response format.
 */
@RestController
class SchedulingController(
    private val schedulingService: SchedulingService,
    private val teamAccessGuard: TeamAccessGuard,
) {

    /**
     * Computes ranked available time windows for a team.
     *
     * Aggregates member availability, subtracts Google Calendar busy blocks and
     * existing events, and returns the top windows sorted by confidence (fraction
     * of members available). Used by the mobile app's scheduling screen and as
     * input to cross-team negotiation proposals.
     *
     * @param teamId UUID of the team to compute windows for.
     * @param request Date range, duration, and optional preferred days.
     * @return 200 OK with a list of [TimeWindowDto] sorted by confidence descending.
     * @throws org.springframework.security.access.AccessDeniedException If the user
     *   is not an active member of the team.
     * @throws IllegalArgumentException If the date range is invalid or duration is
     *   out of bounds.
     * @throws jakarta.persistence.EntityNotFoundException If the team does not exist.
     */
    @PostMapping("/teams/{teamId}/suggest-windows")
    fun suggestWindows(
        @PathVariable teamId: UUID,
        @Valid @RequestBody request: SuggestWindowsRequest,
    ): ResponseEntity<List<TimeWindowDto>> {
        val userId = authenticatedUserId()
        teamAccessGuard.requireActiveMember(userId, teamId)

        val dateRangeStart = LocalDate.parse(request.dateRangeStart)
        val dateRangeEnd = LocalDate.parse(request.dateRangeEnd)

        val windows = schedulingService.findAvailableWindows(
            teamId = teamId,
            dateRangeStart = dateRangeStart,
            dateRangeEnd = dateRangeEnd,
            durationMinutes = request.durationMinutes,
            preferredDays = request.preferredDays,
        )

        val dtos = windows.map { window ->
            TimeWindowDto(
                startsAt = window.startsAt.toString(),
                endsAt = window.endsAt.toString(),
                confidence = window.confidence,
            )
        }

        return ResponseEntity.ok(dtos)
    }
}
