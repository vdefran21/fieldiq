package com.fieldiq.repository

import com.fieldiq.domain.NegotiationProposal
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [NegotiationProposal] entities.
 *
 * Provides queries for retrieving slot proposals within a negotiation session.
 * Proposals are the heart of the negotiation protocol — each round, one side
 * sends a set of proposed time windows, and the other side responds.
 *
 * The unique constraint on (session_id, round_number, proposed_by) ensures
 * idempotency. If a cross-instance HTTP retry sends the same proposal twice,
 * the database rejects the duplicate insert.
 *
 * @see NegotiationProposal for the entity managed by this repository.
 * @see NegotiationSessionRepository for the parent session.
 */
interface NegotiationProposalRepository : JpaRepository<NegotiationProposal, UUID> {

    /**
     * Finds all proposals in a negotiation session, across all rounds and actors.
     *
     * Used to display the full proposal history in the mobile app's negotiation
     * detail view. Returns proposals from both sides (initiator and responder).
     *
     * @param sessionId The UUID of the parent [NegotiationSession].
     * @return All proposals for this session, empty list if none have been sent yet.
     */
    fun findBySessionId(sessionId: UUID): List<NegotiationProposal>

    /**
     * Finds proposals for a specific round within a session.
     *
     * Used by NegotiationService during slot intersection — when processing an
     * incoming proposal for round N, we need to find the local side's proposal
     * for the same round to compute overlapping time windows.
     *
     * @param sessionId The UUID of the parent [NegotiationSession].
     * @param roundNumber The round number to filter by (1-indexed).
     * @return Proposals for this session and round (0-2 results: one per side).
     */
    fun findBySessionIdAndRoundNumber(sessionId: UUID, roundNumber: Int): List<NegotiationProposal>
}
