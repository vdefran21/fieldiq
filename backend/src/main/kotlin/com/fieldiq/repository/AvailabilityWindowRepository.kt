package com.fieldiq.repository

import com.fieldiq.domain.AvailabilityWindow
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AvailabilityWindowRepository : JpaRepository<AvailabilityWindow, UUID> {
    fun findByTeamId(teamId: UUID): List<AvailabilityWindow>
    fun findByUserId(userId: UUID): List<AvailabilityWindow>
    fun findByTeamIdAndUserId(teamId: UUID, userId: UUID): List<AvailabilityWindow>
}
