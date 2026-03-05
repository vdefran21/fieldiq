package com.fieldiq.repository

import com.fieldiq.domain.Team
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeamRepository : JpaRepository<Team, UUID> {
    fun findByOrgId(orgId: UUID): List<Team>
}
