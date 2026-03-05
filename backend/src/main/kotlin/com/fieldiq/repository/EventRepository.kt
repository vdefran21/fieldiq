package com.fieldiq.repository

import com.fieldiq.domain.Event
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface EventRepository : JpaRepository<Event, UUID> {
    fun findByTeamIdAndStartsAtAfterOrderByStartsAtAsc(teamId: UUID, after: Instant): List<Event>
    fun findByTeamId(teamId: UUID): List<Event>
}
