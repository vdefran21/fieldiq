package com.fieldiq.repository

import com.fieldiq.domain.TeamMember
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeamMemberRepository : JpaRepository<TeamMember, UUID> {
    fun findByUserIdAndTeamIdAndIsActiveTrue(userId: UUID, teamId: UUID): TeamMember?
    fun findByTeamIdAndIsActiveTrue(teamId: UUID): List<TeamMember>
    fun findByUserIdAndIsActiveTrue(userId: UUID): List<TeamMember>
}
