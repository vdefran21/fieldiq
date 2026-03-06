package com.fieldiq.service

import com.fieldiq.repository.AvailabilityWindowRepository
import com.fieldiq.repository.EventRepository
import com.fieldiq.repository.OrganizationRepository
import com.fieldiq.repository.TeamMemberRepository
import com.fieldiq.repository.TeamRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Deterministic scheduling engine that computes optimal meeting times for teams.
 *
 * This service is the core of FieldIQ's scheduling intelligence. It aggregates
 * availability declarations from all team members, subtracts busy blocks (from
 * Google Calendar sync and existing events), and produces ranked time windows
 * where the team can meet. No LLM is involved — the algorithm is purely
 * deterministic.
 *
 * **Algorithm overview for [findAvailableWindows]:**
 * 1. Fetch all availability windows for the team (both "available" and "unavailable").
 * 2. Fetch existing scheduled/draft events in the date range as additional conflicts.
 * 3. For each date in the range, compute per-member net-available intervals by
 *    applying "available" windows then subtracting "unavailable" windows and events.
 * 4. Use a sweep-line across all member intervals to find time blocks where
 *    multiple members overlap, scoring each block by the fraction of members available.
 * 5. Extract contiguous blocks >= the requested duration, boost preferred-day scores,
 *    and return the top results sorted by confidence descending.
 *
 * **Algorithm overview for [intersectWindows]:**
 * Finds time ranges where BOTH teams have availability above a minimum confidence
 * threshold. Used during cross-team negotiation to narrow proposals toward
 * mutual availability.
 *
 * All time computations use the team's organization timezone (from [Organization.timezone])
 * to correctly interpret wall-clock times stored in [AvailabilityWindow].
 *
 * @property availabilityWindowRepository Source of member availability declarations.
 * @property eventRepository Source of existing events (for conflict detection).
 * @property teamMemberRepository Source of active team member list.
 * @property teamRepository Source of team metadata (for org lookup).
 * @property organizationRepository Source of organization timezone.
 * @see com.fieldiq.domain.AvailabilityWindow for the raw availability input.
 * @see com.fieldiq.domain.Event for existing events that create conflicts.
 */
@Service
class SchedulingService(
    private val availabilityWindowRepository: AvailabilityWindowRepository,
    private val eventRepository: EventRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamRepository: TeamRepository,
    private val organizationRepository: OrganizationRepository,
) {
    private val logger = LoggerFactory.getLogger(SchedulingService::class.java)

    /**
     * A contiguous time block where some fraction of team members are available.
     *
     * Used as both internal computation result and input to [intersectWindows]
     * during cross-team negotiation.
     *
     * @property startsAt Start of the window in UTC.
     * @property endsAt End of the window in UTC.
     * @property confidence Fraction of team members available (0.0 to 1.0).
     */
    data class TimeWindow(
        val startsAt: Instant,
        val endsAt: Instant,
        val confidence: Double,
    )

    companion object {
        /** Maximum number of windows to return from [findAvailableWindows]. */
        const val MAX_RESULTS = 10

        /** Maximum date range span in days to prevent unbounded computation. */
        const val MAX_DATE_RANGE_DAYS = 60L

        /** Score boost multiplier applied to windows on preferred days. */
        const val PREFERRED_DAY_BOOST = 1.25

        /** Default timezone when team has no organization. */
        val DEFAULT_TIMEZONE: ZoneId = ZoneId.of("America/New_York")
    }

    /**
     * Computes ranked available time windows for a team within a date range.
     *
     * Aggregates all team members' availability, subtracts Google Calendar busy
     * blocks and existing events, then finds contiguous free blocks of at least
     * [durationMinutes]. Windows are ranked by the fraction of members available
     * (confidence), with a boost for windows on [preferredDays].
     *
     * @param teamId UUID of the team to compute windows for.
     * @param dateRangeStart Earliest date to consider (inclusive).
     * @param dateRangeEnd Latest date to consider (inclusive).
     * @param durationMinutes Minimum required duration in minutes.
     * @param preferredDays Optional list of preferred days (0=Sunday through 6=Saturday).
     * @return Up to [MAX_RESULTS] windows sorted by confidence descending.
     * @throws EntityNotFoundException If the team does not exist.
     * @throws IllegalArgumentException If dateRangeEnd is before dateRangeStart,
     *   or the range exceeds [MAX_DATE_RANGE_DAYS].
     */
    fun findAvailableWindows(
        teamId: UUID,
        dateRangeStart: LocalDate,
        dateRangeEnd: LocalDate,
        durationMinutes: Int,
        preferredDays: List<Int>? = null,
    ): List<TimeWindow> {
        require(!dateRangeEnd.isBefore(dateRangeStart)) {
            "dateRangeEnd must not be before dateRangeStart"
        }
        require(ChronoUnit.DAYS.between(dateRangeStart, dateRangeEnd) <= MAX_DATE_RANGE_DAYS) {
            "Date range must not exceed $MAX_DATE_RANGE_DAYS days"
        }
        require(durationMinutes >= 15) { "Duration must be at least 15 minutes" }

        val team = teamRepository.findById(teamId)
            .orElseThrow { EntityNotFoundException("Team $teamId not found") }

        val timezone = resolveTimezone(team.orgId)
        val activeMembers = teamMemberRepository.findByTeamIdAndIsActiveTrue(teamId)

        if (activeMembers.isEmpty()) {
            logger.info("Team {} has no active members, returning empty windows", teamId)
            return emptyList()
        }

        val memberUserIds = activeMembers.map { it.userId }.toSet()
        val allWindows = availabilityWindowRepository.findByTeamId(teamId)

        // Convert date range to instants for event query
        val rangeStartInstant = dateRangeStart.atStartOfDay(timezone).toInstant()
        val rangeEndInstant = dateRangeEnd.plusDays(1).atStartOfDay(timezone).toInstant()
        val existingEvents = eventRepository.findOverlappingEvents(teamId, rangeStartInstant, rangeEndInstant)

        val preferredDaySet = preferredDays?.toSet() ?: emptySet()
        val results = mutableListOf<TimeWindow>()

        // Process each date in the range
        var currentDate = dateRangeStart
        while (!currentDate.isAfter(dateRangeEnd)) {
            val javaDow = currentDate.dayOfWeek // java.time.DayOfWeek (MONDAY=1)
            val fieldiqDow = javaDowToFieldiq(javaDow) // 0=Sunday convention

            val dateWindows = computeWindowsForDate(
                currentDate = currentDate,
                fieldiqDow = fieldiqDow,
                memberUserIds = memberUserIds,
                allWindows = allWindows,
                existingEvents = existingEvents,
                timezone = timezone,
                durationMinutes = durationMinutes,
            )

            // Apply preferred day boost
            val boosted = if (fieldiqDow in preferredDaySet) {
                dateWindows.map { it.copy(confidence = (it.confidence * PREFERRED_DAY_BOOST).coerceAtMost(1.0)) }
            } else {
                dateWindows
            }

            results.addAll(boosted)
            currentDate = currentDate.plusDays(1)
        }

        // Sort by confidence descending, take top N
        return results.sortedByDescending { it.confidence }.take(MAX_RESULTS)
    }

    /**
     * Finds the intersection of two teams' available time windows.
     *
     * Given ranked windows from two teams (computed by [findAvailableWindows]),
     * identifies time ranges where BOTH teams have availability. The combined
     * confidence uses the minimum of the two teams' confidences (conservative
     * estimate — the bottleneck team determines the overall confidence).
     *
     * Used during cross-team negotiation when two FieldIQ instances exchange
     * proposed time slots and attempt to find mutual availability.
     *
     * @param teamAWindows Available windows for team A, from [findAvailableWindows].
     * @param teamBWindows Available windows for team B, from [findAvailableWindows].
     * @param minimumConfidence Minimum combined confidence threshold. Windows below
     *   this are filtered out. Defaults to 0.5 (at least half of each team available).
     * @return Intersection windows sorted by combined confidence descending.
     */
    fun intersectWindows(
        teamAWindows: List<TimeWindow>,
        teamBWindows: List<TimeWindow>,
        minimumConfidence: Double = 0.5,
    ): List<TimeWindow> {
        val intersections = mutableListOf<TimeWindow>()

        for (a in teamAWindows) {
            for (b in teamBWindows) {
                val overlapStart = maxOf(a.startsAt, b.startsAt)
                val overlapEnd = minOf(a.endsAt, b.endsAt)

                if (overlapStart.isBefore(overlapEnd)) {
                    val combinedConfidence = minOf(a.confidence, b.confidence)
                    if (combinedConfidence >= minimumConfidence) {
                        intersections.add(
                            TimeWindow(
                                startsAt = overlapStart,
                                endsAt = overlapEnd,
                                confidence = combinedConfidence,
                            )
                        )
                    }
                }
            }
        }

        return intersections.sortedByDescending { it.confidence }
    }

    /**
     * Computes available time windows for a single date.
     *
     * For each team member, builds their net-available intervals by applying
     * "available" windows and subtracting "unavailable" windows and existing
     * events. Then uses a sweep-line across all members to find time blocks
     * where multiple members overlap, and extracts blocks >= the requested
     * duration as candidate windows.
     *
     * @param currentDate The specific date to analyze.
     * @param fieldiqDow Day of week in FieldIQ convention (0=Sunday).
     * @param memberUserIds Set of active team member user IDs.
     * @param allWindows All availability windows for the team.
     * @param existingEvents Existing events that create conflicts.
     * @param timezone Organization timezone for wall-clock interpretation.
     * @param durationMinutes Minimum required duration in minutes.
     * @return Available windows for this date, unranked (caller applies boosts).
     */
    private fun computeWindowsForDate(
        currentDate: LocalDate,
        fieldiqDow: Int,
        memberUserIds: Set<UUID>,
        allWindows: List<com.fieldiq.domain.AvailabilityWindow>,
        existingEvents: List<com.fieldiq.domain.Event>,
        timezone: ZoneId,
        durationMinutes: Int,
    ): List<TimeWindow> {
        val totalMembers = memberUserIds.size

        // Build per-member net-available intervals for this date
        val memberIntervals = mutableMapOf<UUID, List<TimeInterval>>()

        for (userId in memberUserIds) {
            val userWindows = allWindows.filter { it.userId == userId }

            // Collect applicable "available" intervals
            val available = userWindows
                .filter { it.windowType == "available" && matchesDate(it, currentDate, fieldiqDow) }
                .map { TimeInterval(it.startTime, it.endTime) }
            val mergedAvailable = mergeIntervals(available)

            // Collect applicable "unavailable" intervals
            val unavailable = userWindows
                .filter { it.windowType == "unavailable" && matchesDate(it, currentDate, fieldiqDow) }
                .map { TimeInterval(it.startTime, it.endTime) }

            // Collect event conflicts (convert Instants to LocalTime on this date)
            val eventConflicts = existingEvents
                .mapNotNull { event -> eventToLocalInterval(event, currentDate, timezone) }

            val allSubtractions = mergeIntervals(unavailable + eventConflicts)

            // Net available = available - unavailable - events
            val netAvailable = subtractIntervals(mergedAvailable, allSubtractions)
            if (netAvailable.isNotEmpty()) {
                memberIntervals[userId] = netAvailable
            }
        }

        if (memberIntervals.isEmpty()) {
            return emptyList()
        }

        // Sweep-line: collect all interval endpoints and count members at each point
        val events = mutableListOf<SweepEvent>()
        for ((_, intervals) in memberIntervals) {
            for (interval in intervals) {
                events.add(SweepEvent(interval.start, isStart = true))
                events.add(SweepEvent(interval.end, isStart = false))
            }
        }
        events.sortWith(compareBy<SweepEvent> { it.time }.thenBy { if (it.isStart) 0 else 1 })

        // Walk the sweep line to find contiguous blocks with member counts
        val candidateBlocks = mutableListOf<ScoredBlock>()
        var activeCount = 0
        var blockStart: LocalTime? = null

        for (i in events.indices) {
            val prev = activeCount
            if (events[i].isStart) activeCount++ else activeCount--

            // Transition from 0 to >0: start a block
            if (prev == 0 && activeCount > 0) {
                blockStart = events[i].time
            }

            // Transition from >0 to 0: end a block
            if (prev > 0 && activeCount == 0 && blockStart != null) {
                candidateBlocks.add(ScoredBlock(blockStart, events[i].time, 0.0))
                blockStart = null
            }

            // Count change within a block: split for different confidence levels
            if (prev > 0 && activeCount > 0 && prev != activeCount && blockStart != null) {
                val currentTime = events[i].time
                if (currentTime.isAfter(blockStart)) {
                    candidateBlocks.add(
                        ScoredBlock(blockStart, currentTime, prev.toDouble() / totalMembers)
                    )
                }
                blockStart = currentTime
            }
        }

        // Now we have blocks with uniform member counts. Recalculate confidence for
        // the blocks that were just split at count transitions but not yet scored.
        // Actually, let's redo this more cleanly with a proper sweep.
        return computeWindowsFromSweep(events, totalMembers, currentDate, timezone, durationMinutes)
    }

    /**
     * Extracts available time windows from sweep-line events using a segment-based approach.
     *
     * Walks through sorted sweep events, tracking the number of available members at
     * each point. Produces uniform-confidence segments, then scans for contiguous blocks
     * of at least [durationMinutes] where at least one member is available.
     *
     * Segments are contiguous only if one ends exactly where the next begins. A gap
     * between segments (e.g., 11:00-13:00 with zero count) breaks the run and may
     * produce multiple separate windows.
     *
     * @param sweepEvents Sorted list of interval start/end events.
     * @param totalMembers Total active members on the team (denominator for confidence).
     * @param date The date being analyzed.
     * @param timezone Organization timezone for converting to UTC instants.
     * @param durationMinutes Minimum required block duration.
     * @return Time windows meeting the duration requirement with their confidence scores.
     */
    private fun computeWindowsFromSweep(
        sweepEvents: List<SweepEvent>,
        totalMembers: Int,
        date: LocalDate,
        timezone: ZoneId,
        durationMinutes: Int,
    ): List<TimeWindow> {
        if (sweepEvents.isEmpty()) return emptyList()

        // Build segments with uniform member counts
        data class Segment(val start: LocalTime, val end: LocalTime, val count: Int)

        val segments = mutableListOf<Segment>()
        var activeCount = 0

        for (i in sweepEvents.indices) {
            val event = sweepEvents[i]

            if (activeCount > 0 && i > 0) {
                val prevTime = sweepEvents[i - 1].time
                if (event.time.isAfter(prevTime)) {
                    segments.add(Segment(prevTime, event.time, activeCount))
                }
            }

            if (event.isStart) activeCount++ else activeCount--
        }

        if (segments.isEmpty()) return emptyList()

        // Find contiguous runs of segments where count > 0 and total duration >= durationMinutes.
        // Segments are contiguous only when segment[i].start == segment[i-1].end.
        val results = mutableListOf<TimeWindow>()
        var runStart: LocalTime? = null
        var runEnd: LocalTime? = null
        var runMinCount = 0

        for (segment in segments) {
            if (segment.count > 0) {
                // Check if this segment is contiguous with the current run
                if (runStart == null || runEnd != segment.start) {
                    // Flush any existing run before starting a new one
                    if (runStart != null && runEnd != null) {
                        emitWindow(runStart, runEnd, runMinCount, totalMembers, date, timezone, durationMinutes, results)
                    }
                    runStart = segment.start
                    runMinCount = segment.count
                } else {
                    runMinCount = minOf(runMinCount, segment.count)
                }
                runEnd = segment.end
            } else {
                // Zero-count segment ends the current run
                if (runStart != null && runEnd != null) {
                    emitWindow(runStart, runEnd, runMinCount, totalMembers, date, timezone, durationMinutes, results)
                }
                runStart = null
                runEnd = null
                runMinCount = 0
            }
        }

        // Flush final run
        if (runStart != null && runEnd != null) {
            emitWindow(runStart, runEnd, runMinCount, totalMembers, date, timezone, durationMinutes, results)
        }

        return results
    }

    /**
     * Creates a [TimeWindow] if the block meets the minimum duration, and appends it to results.
     *
     * @param start Block start as wall-clock time.
     * @param end Block end as wall-clock time.
     * @param minCount Minimum member count across the block.
     * @param totalMembers Total active team members for confidence calculation.
     * @param date The date of this block.
     * @param timezone Timezone for LocalTime to Instant conversion.
     * @param durationMinutes Minimum required duration in minutes.
     * @param results The mutable list to append qualifying windows to.
     */
    private fun emitWindow(
        start: LocalTime,
        end: LocalTime,
        minCount: Int,
        totalMembers: Int,
        date: LocalDate,
        timezone: ZoneId,
        durationMinutes: Int,
        results: MutableList<TimeWindow>,
    ) {
        val durationMins = java.time.Duration.between(start, end).toMinutes()
        if (durationMins >= durationMinutes) {
            val startInstant = start.atDate(date).atZone(timezone).toInstant()
            val endInstant = end.atDate(date).atZone(timezone).toInstant()
            results.add(
                TimeWindow(
                    startsAt = startInstant,
                    endsAt = endInstant,
                    confidence = minCount.toDouble() / totalMembers,
                )
            )
        }
    }

    /**
     * Resolves the IANA timezone for a team via its parent organization.
     *
     * Falls back to [DEFAULT_TIMEZONE] (America/New_York) for teams without
     * an organization assignment. This is reasonable for Phase 1 since the
     * target market is DMV (DC/Maryland/Virginia) youth soccer.
     *
     * @param orgId The organization UUID, or null for standalone teams.
     * @return The resolved [ZoneId].
     */
    private fun resolveTimezone(orgId: UUID?): ZoneId {
        if (orgId == null) return DEFAULT_TIMEZONE
        val org = organizationRepository.findById(orgId).orElse(null) ?: return DEFAULT_TIMEZONE
        return try {
            ZoneId.of(org.timezone)
        } catch (e: Exception) {
            logger.warn("Invalid timezone '{}' for org {}, falling back to default", org.timezone, orgId)
            DEFAULT_TIMEZONE
        }
    }

    /**
     * Checks whether an availability window applies to a specific date.
     *
     * A window applies if it's a recurring window matching the date's day of week,
     * or a specific-date window matching the exact date.
     *
     * @param window The availability window to check.
     * @param date The target date.
     * @param fieldiqDow The date's day of week in FieldIQ convention (0=Sunday).
     * @return True if the window applies to this date.
     */
    private fun matchesDate(
        window: com.fieldiq.domain.AvailabilityWindow,
        date: LocalDate,
        fieldiqDow: Int,
    ): Boolean {
        return when {
            window.dayOfWeek != null -> window.dayOfWeek.toInt() == fieldiqDow
            window.specificDate != null -> window.specificDate == date
            else -> false
        }
    }

    /**
     * Converts an event's UTC time range to a [TimeInterval] on a specific local date.
     *
     * Handles events that span midnight or partially overlap the target date by
     * clamping to [LocalTime.MIN] and [LocalTime.MAX]. Returns null if the event
     * does not overlap the target date at all.
     *
     * @param event The event with UTC start/end instants.
     * @param date The target local date.
     * @param timezone The timezone for conversion.
     * @return A [TimeInterval] on the target date, or null if no overlap.
     */
    private fun eventToLocalInterval(
        event: com.fieldiq.domain.Event,
        date: LocalDate,
        timezone: ZoneId,
    ): TimeInterval? {
        val eventStart = event.startsAt ?: return null
        val eventEnd = event.endsAt ?: return null

        val eventStartLocal = eventStart.atZone(timezone).toLocalDate()
        val eventEndLocal = eventEnd.atZone(timezone).toLocalDate()

        // Check if event overlaps this date at all
        if (eventEndLocal.isBefore(date) || eventStartLocal.isAfter(date)) {
            return null
        }

        val startTime = if (eventStartLocal.isBefore(date)) {
            LocalTime.MIN
        } else {
            eventStart.atZone(timezone).toLocalTime()
        }

        val endTime = if (eventEndLocal.isAfter(date)) {
            LocalTime.MAX
        } else {
            eventEnd.atZone(timezone).toLocalTime()
        }

        return if (startTime.isBefore(endTime)) TimeInterval(startTime, endTime) else null
    }

    /**
     * Converts java.time.DayOfWeek to FieldIQ's 0-based Sunday-start convention.
     *
     * java.time uses 1=Monday through 7=Sunday. FieldIQ uses 0=Sunday through
     * 6=Saturday (matching JavaScript's Date.getDay() and the US calendar convention
     * typical for youth sports scheduling).
     *
     * @param dow The java.time [java.time.DayOfWeek].
     * @return FieldIQ day of week: 0=Sunday, 1=Monday, ..., 6=Saturday.
     */
    private fun javaDowToFieldiq(dow: java.time.DayOfWeek): Int {
        return when (dow) {
            java.time.DayOfWeek.SUNDAY -> 0
            java.time.DayOfWeek.MONDAY -> 1
            java.time.DayOfWeek.TUESDAY -> 2
            java.time.DayOfWeek.WEDNESDAY -> 3
            java.time.DayOfWeek.THURSDAY -> 4
            java.time.DayOfWeek.FRIDAY -> 5
            java.time.DayOfWeek.SATURDAY -> 6
        }
    }

    // =========================================================================
    // Interval arithmetic utilities
    // =========================================================================

    /**
     * A time interval on a single day, represented as wall-clock times.
     *
     * @property start Start of the interval (inclusive).
     * @property end End of the interval (exclusive).
     */
    data class TimeInterval(val start: LocalTime, val end: LocalTime)

    /**
     * Sweep-line event for tracking member availability count transitions.
     *
     * @property time The wall-clock time of this event.
     * @property isStart True if a member's available interval starts here, false if it ends.
     */
    private data class SweepEvent(val time: LocalTime, val isStart: Boolean)

    /**
     * Internal scored block before final TimeWindow conversion.
     *
     * @property start Block start time.
     * @property end Block end time.
     * @property confidence Fraction of team members available.
     */
    private data class ScoredBlock(val start: LocalTime, val end: LocalTime, val confidence: Double)

    /**
     * Merges overlapping or adjacent time intervals into a minimal set.
     *
     * @param intervals Unordered list of time intervals.
     * @return Sorted, non-overlapping intervals covering the same total time.
     */
    private fun mergeIntervals(intervals: List<TimeInterval>): List<TimeInterval> {
        if (intervals.isEmpty()) return emptyList()

        val sorted = intervals.sortedBy { it.start }
        val merged = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val current = sorted[i]
            val last = merged.last()

            if (!current.start.isAfter(last.end)) {
                // Overlapping or adjacent — extend
                merged[merged.lastIndex] = TimeInterval(last.start, maxOf(last.end, current.end))
            } else {
                merged.add(current)
            }
        }

        return merged
    }

    /**
     * Subtracts a set of intervals from another set.
     *
     * Computes the set difference: removes all time covered by [toSubtract] from
     * [base]. Both inputs should be pre-merged (non-overlapping, sorted).
     *
     * @param base The base intervals to subtract from.
     * @param toSubtract The intervals to remove.
     * @return Remaining intervals after subtraction.
     */
    private fun subtractIntervals(
        base: List<TimeInterval>,
        toSubtract: List<TimeInterval>,
    ): List<TimeInterval> {
        if (base.isEmpty() || toSubtract.isEmpty()) return base

        val result = mutableListOf<TimeInterval>()

        for (b in base) {
            var remaining = listOf(b)
            for (s in toSubtract) {
                remaining = remaining.flatMap { subtractSingle(it, s) }
            }
            result.addAll(remaining)
        }

        return result
    }

    /**
     * Subtracts a single interval from another, returning 0-2 remaining fragments.
     *
     * @param base The interval to subtract from.
     * @param sub The interval to subtract.
     * @return Remaining fragments: 0 (fully consumed), 1 (partial overlap), or
     *   2 (sub punches a hole in the middle of base).
     */
    private fun subtractSingle(base: TimeInterval, sub: TimeInterval): List<TimeInterval> {
        // No overlap
        if (!sub.start.isBefore(base.end) || !sub.end.isAfter(base.start)) {
            return listOf(base)
        }

        val fragments = mutableListOf<TimeInterval>()

        // Left fragment
        if (sub.start.isAfter(base.start)) {
            fragments.add(TimeInterval(base.start, sub.start))
        }

        // Right fragment
        if (sub.end.isBefore(base.end)) {
            fragments.add(TimeInterval(sub.end, base.end))
        }

        return fragments
    }
}
