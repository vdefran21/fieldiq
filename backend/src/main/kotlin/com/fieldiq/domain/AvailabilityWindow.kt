package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Represents a time block when a team member is available or unavailable.
 *
 * Availability windows are the raw input to [SchedulingService], which aggregates
 * windows across all team members to find optimal meeting times. Windows can be
 * either recurring (every Saturday 9am-12pm) or date-specific (March 15th 10am-11am),
 * but never both — enforced by the `chk_window_date_type` CHECK constraint.
 *
 * Windows come from two sources:
 * - **Manual entry:** Parents/coaches declare their availability in the mobile app.
 * - **Google Calendar sync:** The agent layer's `SYNC_CALENDAR` worker reads busy blocks
 *   from Google Calendar and creates "unavailable" windows. These are refreshed
 *   periodically and replaced on each sync cycle.
 *
 * The `chk_window_time_order` CHECK constraint ensures [startTime] < [endTime]
 * (no zero-duration or inverted windows).
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property teamId Foreign key to the [Team] this window applies to. A user may
 *   have different availability on different teams.
 * @property userId Foreign key to the [User] who declared this availability.
 * @property dayOfWeek For recurring windows: day of week as 0-6 (Sunday=0 through
 *   Saturday=6). Mutually exclusive with [specificDate] — exactly one must be set.
 * @property specificDate For one-time windows: the specific calendar date this
 *   window applies to. Mutually exclusive with [dayOfWeek].
 * @property startTime Start of the availability window as a wall-clock time (no timezone).
 *   Interpreted in the team's organization timezone.
 * @property endTime End of the availability window. Must be after [startTime] (enforced
 *   by CHECK constraint). Interpreted in the team's organization timezone.
 * @property windowType Whether this block represents free time ("available") or
 *   a conflict ("unavailable"). SchedulingService subtracts "unavailable" blocks
 *   from the team's combined schedule.
 * @property source How this window was created: "manual" (user-entered), "google_cal"
 *   (synced from Google Calendar), or "apple_cal" (future: Apple Calendar sync).
 * @property createdAt Timestamp of window creation, set once and never updated.
 * @see com.fieldiq.service.SchedulingService for the service that consumes these windows.
 */
@Entity
@Table(name = "availability_windows")
data class AvailabilityWindow(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "team_id", nullable = false)
    val teamId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "day_of_week")
    val dayOfWeek: Short? = null,

    @Column(name = "specific_date")
    val specificDate: LocalDate? = null,

    @Column(name = "start_time", nullable = false)
    val startTime: LocalTime,

    @Column(name = "end_time", nullable = false)
    val endTime: LocalTime,

    @Column(name = "window_type", nullable = false)
    val windowType: String,

    @Column(nullable = false)
    val source: String = "manual",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
