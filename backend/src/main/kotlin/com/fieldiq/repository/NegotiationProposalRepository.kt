package com.fieldiq.repository

import com.fieldiq.domain.NegotiationProposal
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NegotiationProposalRepository : JpaRepository<NegotiationProposal, UUID> {
    fun findBySessionId(sessionId: UUID): List<NegotiationProposal>
    fun findBySessionIdAndRoundNumber(sessionId: UUID, roundNumber: Int): List<NegotiationProposal>
}
