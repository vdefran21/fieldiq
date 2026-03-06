package com.fieldiq.api.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * Request body for `POST /teams/:teamId/suggest-windows`.
 *
 * Asks [com.fieldiq.service.SchedulingService] to compute optimal meeting times
 * for a team based on member availability, Google Calendar busy blocks, and
 * existing events. This is a deterministic algorithm (no LLM) that runs in
 * the Kotlin backend.
 *
 * Corresponds to TypeScript interface: `SuggestWindowsRequest` in `shared/types/index.ts`.
 *
 * @property dateRangeStart Earliest acceptable date in YYYY-MM-DD format. Must be
 *   today or in the future.
 * @property dateRangeEnd Latest acceptable date in YYYY-MM-DD format. Must be after
 *   [dateRangeStart] and at most 60 days out (to keep computation bounded).
 * @property durationMinutes Required duration in minutes. Typical values: 60 (practice),
 *   90 (standard youth soccer game), 120 (tournament match with warmup).
 * @property preferredDays Optional list of preferred days of week (0=Sunday through
 *   6=Saturday). Windows on these days receive a score boost in ranking.
 */
data class SuggestWindowsRequest(
    @field:NotBlank(message = "Date range start is required")
    val dateRangeStart: String,

    @field:NotBlank(message = "Date range end is required")
    val dateRangeEnd: String,

    @field:NotNull(message = "Duration is required")
    @field:Min(value = 15, message = "Duration must be at least 15 minutes")
    @field:Max(value = 480, message = "Duration must be at most 480 minutes")
    val durationMinutes: Int,

    val preferredDays: List<Int>? = null,
)

/**
 * A computed time window returned by the scheduling algorithm.
 *
 * Represents a contiguous time block where some percentage of team members are
 * available. Ranked by [confidence] (fraction of team members free during
 * this window). Used both for single-team scheduling suggestions and as input
 * to cross-team negotiation (where two teams' windows are intersected).
 *
 * Corresponds to TypeScript interface: `TimeWindowDto` in `shared/types/index.ts`.
 * Corresponds to Kotlin data class: `SchedulingService.TimeWindow`.
 *
 * @property startsAt Window start time in ISO 8601 UTC format.
 * @property endsAt Window end time in ISO 8601 UTC format.
 * @property confidence Fraction of team members available during this window (0.0 to 1.0).
 *   A confidence of 1.0 means every active member is free; 0.5 means half are free.
 */
data class TimeWindowDto(
    val startsAt: String,
    val endsAt: String,
    val confidence: Double,
)
