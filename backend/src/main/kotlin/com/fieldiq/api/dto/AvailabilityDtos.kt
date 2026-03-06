package com.fieldiq.api.dto

import com.fieldiq.domain.AvailabilityWindow
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Request body for `POST /users/me/availability`.
 *
 * Declares a new availability window for the authenticated user.
 * Exactly one of [dayOfWeek] or [specificDate] must be provided (enforced
 * by DB CHECK constraint and service-layer validation).
 *
 * Corresponds to TypeScript interface: `CreateAvailabilityWindowRequest` in `shared/types/index.ts`.
 *
 * @property teamId UUID of the team this availability is for. The user must be an
 *   active member of this team.
 * @property dayOfWeek For recurring windows: day of week (0=Sunday through 6=Saturday).
 *   Mutually exclusive with [specificDate].
 * @property specificDate For one-time windows: date in YYYY-MM-DD format.
 *   Mutually exclusive with [dayOfWeek].
 * @property startTime Start time in HH:mm format, interpreted in the org's timezone.
 * @property endTime End time in HH:mm format. Must be after [startTime].
 * @property windowType Whether this block represents free time ("available") or a
 *   conflict ("unavailable").
 */
data class CreateAvailabilityWindowRequest(
    @field:NotBlank(message = "Team ID is required")
    val teamId: String,

    val dayOfWeek: Short? = null,

    val specificDate: String? = null,

    @field:NotBlank(message = "Start time is required")
    val startTime: String,

    @field:NotBlank(message = "End time is required")
    val endTime: String,

    @field:NotBlank(message = "Window type is required")
    @field:Pattern(regexp = "available|unavailable", message = "Window type must be 'available' or 'unavailable'")
    val windowType: String,
)

/**
 * Availability window data returned in API responses.
 *
 * Represents a time block when a team member is available or unavailable.
 * Used by the SchedulingService to compute optimal meeting times.
 *
 * Corresponds to TypeScript interface: `AvailabilityWindowDto` in `shared/types/index.ts`.
 * Corresponds to Kotlin entity: [com.fieldiq.domain.AvailabilityWindow].
 *
 * @property id UUID of the availability window.
 * @property teamId UUID of the team this window applies to.
 * @property userId UUID of the user who declared this window.
 * @property dayOfWeek For recurring windows: day of week (0=Sunday through 6=Saturday).
 * @property specificDate For one-time windows: specific date in YYYY-MM-DD format.
 * @property startTime Start time in HH:mm format.
 * @property endTime End time in HH:mm format.
 * @property windowType "available" or "unavailable".
 * @property source How this window was created: "manual", "google_cal", or "apple_cal".
 */
data class AvailabilityWindowDto(
    val id: String,
    val teamId: String,
    val userId: String,
    val dayOfWeek: Short? = null,
    val specificDate: String? = null,
    val startTime: String,
    val endTime: String,
    val windowType: String,
    val source: String,
) {
    companion object {
        /**
         * Converts an [AvailabilityWindow] entity to an [AvailabilityWindowDto] response object.
         *
         * @param window The availability window entity to convert.
         * @return An [AvailabilityWindowDto] with all fields mapped.
         */
        fun from(window: AvailabilityWindow): AvailabilityWindowDto = AvailabilityWindowDto(
            id = window.id.toString(),
            teamId = window.teamId.toString(),
            userId = window.userId.toString(),
            dayOfWeek = window.dayOfWeek,
            specificDate = window.specificDate?.toString(),
            startTime = window.startTime.toString(),
            endTime = window.endTime.toString(),
            windowType = window.windowType,
            source = window.source,
        )
    }
}
