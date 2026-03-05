package com.fieldiq.repository

import com.fieldiq.domain.NegotiationSession
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NegotiationSessionRepository : JpaRepository<NegotiationSession, UUID> {
    fun findByInviteToken(inviteToken: String): NegotiationSession?
    fun findByInitiatorTeamId(teamId: UUID): List<NegotiationSession>
    fun findByStatus(status: String): List<NegotiationSession>
}
