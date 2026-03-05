package com.fieldiq.service

import com.fieldiq.domain.TeamMember
import com.fieldiq.repository.TeamMemberRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TeamAccessGuard(
    private val teamMemberRepository: TeamMemberRepository
) {
    fun requireActiveMember(userId: UUID, teamId: UUID): TeamMember {
        return teamMemberRepository.findByUserIdAndTeamIdAndIsActiveTrue(userId, teamId)
            ?: throw AccessDeniedException("User $userId is not an active member of team $teamId")
    }

    fun requireManager(userId: UUID, teamId: UUID): TeamMember {
        val member = requireActiveMember(userId, teamId)
        if (member.role != "manager") {
            throw AccessDeniedException("User $userId is not a manager of team $teamId")
        }
        return member
    }
}
