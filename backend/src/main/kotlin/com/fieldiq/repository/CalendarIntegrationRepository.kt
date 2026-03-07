package com.fieldiq.repository

import com.fieldiq.domain.CalendarIntegration
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [CalendarIntegration] entities.
 *
 * Provides queries for managing Google Calendar OAuth tokens. The primary
 * lookup is by user ID, since each user has at most one calendar integration.
 *
 * @see CalendarIntegration for the entity managed by this repository.
 * @see com.fieldiq.service.GoogleCalendarService for the service that uses this repository.
 */
interface CalendarIntegrationRepository : JpaRepository<CalendarIntegration, UUID> {

    /**
     * Finds the calendar integration for a specific user.
     *
     * Returns at most one result due to the UNIQUE constraint on `user_id`.
     * Returns null if the user has not connected their Google Calendar.
     *
     * @param userId The UUID of the user.
     * @return The user's [CalendarIntegration], or null if not connected.
     */
    fun findByUserId(userId: UUID): CalendarIntegration?
}
