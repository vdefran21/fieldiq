package com.fieldiq.service

import com.fieldiq.api.dto.AvailabilityWindowDto
import com.fieldiq.api.dto.CreateAvailabilityWindowRequest
import com.fieldiq.domain.AvailabilityWindow
import com.fieldiq.repository.AvailabilityWindowRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Business logic for availability window management.
 *
 * Handles creating, querying, and deleting availability windows that feed into
 * the [SchedulingService]. Parents and coaches declare when they are available
 * or unavailable, and the scheduling engine aggregates these windows across all
 * team members to find optimal game times.
 *
 * **Validation rules:**
 * - Exactly one of `dayOfWeek` or `specificDate` must be set (not both, not neither).
 * - `startTime` must be before `endTime` (no zero-duration or inverted windows).
 * - The user must be an active member of the specified team.
 *
 * These rules mirror the database CHECK constraints (`chk_window_date_type`,
 * `chk_window_time_order`) but are enforced at the service layer first for
 * better error messages.
 *
 * **Database impact:** Writes to the `availability_windows` table. All write
 * operations are wrapped in `@Transactional`.
 *
 * @property availabilityWindowRepository Repository for [AvailabilityWindow] CRUD and queries.
 * @property teamAccessGuard Multi-tenancy guard for membership checks.
 * @see com.fieldiq.api.AvailabilityController for the REST endpoints that delegate to this service.
 * @see TeamAccessGuard for access control enforcement.
 */
@Service
class AvailabilityWindowService(
    private val availabilityWindowRepository: AvailabilityWindowRepository,
    private val teamAccessGuard: TeamAccessGuard,
) {
    private val logger = LoggerFactory.getLogger(AvailabilityWindowService::class.java)

    /**
     * Creates a new availability window for the authenticated user.
     *
     * Validates the request (date type exclusivity, time ordering), checks team
     * membership, and persists the window. The window is associated with both the
     * user and the specified team — a user may have different availability across
     * different teams.
     *
     * @param userId The UUID of the authenticated user declaring availability.
     * @param request The window details: team, day/date, start/end times, type.
     * @return An [AvailabilityWindowDto] representing the newly created window.
     * @throws IllegalArgumentException If both or neither of dayOfWeek/specificDate are provided,
     *   or if startTime is not before endTime.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not an active member of the team.
     */
    @Transactional
    fun createWindow(userId: UUID, request: CreateAvailabilityWindowRequest): AvailabilityWindowDto {
        // Validate: exactly one of dayOfWeek or specificDate
        if (request.dayOfWeek != null && request.specificDate != null) {
            throw IllegalArgumentException("Only one of dayOfWeek or specificDate can be specified, not both")
        }
        if (request.dayOfWeek == null && request.specificDate == null) {
            throw IllegalArgumentException("Exactly one of dayOfWeek or specificDate must be specified")
        }

        val startTime = LocalTime.parse(request.startTime)
        val endTime = LocalTime.parse(request.endTime)

        // Validate: startTime < endTime
        if (!startTime.isBefore(endTime)) {
            throw IllegalArgumentException("Start time must be before end time")
        }

        val teamId = UUID.fromString(request.teamId)
        teamAccessGuard.requireActiveMember(userId, teamId)

        val window = availabilityWindowRepository.save(
            AvailabilityWindow(
                teamId = teamId,
                userId = userId,
                dayOfWeek = request.dayOfWeek,
                specificDate = request.specificDate?.let { LocalDate.parse(it) },
                startTime = startTime,
                endTime = endTime,
                windowType = request.windowType,
                source = "manual",
            )
        )

        logger.info("Availability window created for user {} on team {}", userId, teamId)
        return AvailabilityWindowDto.from(window)
    }

    /**
     * Lists all availability windows for a team across all members.
     *
     * Used by the scheduling engine and by managers to view team-wide availability.
     * Returns windows of all types ("available" and "unavailable") from all members.
     * Requires the requesting user to be an active team member.
     *
     * @param userId The UUID of the authenticated user requesting the data.
     * @param teamId The UUID of the team whose availability to list.
     * @return A list of [AvailabilityWindowDto] for all team members.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not an active member.
     */
    fun getTeamAvailability(userId: UUID, teamId: UUID): List<AvailabilityWindowDto> {
        teamAccessGuard.requireActiveMember(userId, teamId)
        val windows = availabilityWindowRepository.findByTeamId(teamId)
        return windows.map { AvailabilityWindowDto.from(it) }
    }

    /**
     * Lists all availability windows declared by the authenticated user.
     *
     * Returns windows across all teams the user belongs to. Used in the mobile
     * app's personal availability management screen.
     *
     * @param userId The UUID of the authenticated user.
     * @return A list of [AvailabilityWindowDto] for the user across all teams.
     */
    fun getUserAvailability(userId: UUID): List<AvailabilityWindowDto> {
        val windows = availabilityWindowRepository.findByUserId(userId)
        return windows.map { AvailabilityWindowDto.from(it) }
    }

    /**
     * Deletes an availability window owned by the authenticated user.
     *
     * Only the user who created the window can delete it. This enforces that
     * managers cannot delete other users' availability declarations — each member
     * manages their own windows.
     *
     * @param userId The UUID of the authenticated user requesting deletion.
     * @param windowId The UUID of the availability window to delete.
     * @throws EntityNotFoundException If the window does not exist.
     * @throws AccessDeniedException If the window belongs to a different user.
     */
    @Transactional
    fun deleteWindow(userId: UUID, windowId: UUID) {
        val window = availabilityWindowRepository.findById(windowId)
            .orElseThrow { EntityNotFoundException("Availability window $windowId not found") }

        if (window.userId != userId) {
            throw AccessDeniedException("User $userId does not own availability window $windowId")
        }

        availabilityWindowRepository.delete(window)
        logger.info("Availability window {} deleted by user {}", windowId, userId)
    }
}
