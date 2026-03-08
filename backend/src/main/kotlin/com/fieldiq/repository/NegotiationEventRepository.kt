package com.fieldiq.repository

import com.fieldiq.domain.NegotiationEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [NegotiationEvent] entities.
 *
 * Provides queries for the append-only audit log of negotiation state changes.
 * Events are immutable — once created they are never updated or deleted.
 * This repository is write-heavy (every state transition produces an event)
 * and read-occasional (audit review, timeline display in mobile app).
 *
 * @see NegotiationEvent for the entity managed by this repository.
 * @see NegotiationSessionRepository for the parent session.
 * @see NegotiationProposalRepository for the proposals within a session.
 */
interface NegotiationEventRepository : JpaRepository<NegotiationEvent, UUID> {

    /**
     * Finds all audit events for a negotiation session, in insertion order.
     *
     * Used to display a timeline of negotiation actions in the mobile app
     * (e.g., "Session created", "Opponent joined", "Proposal sent", "Match found").
     * Also used for debugging failed negotiations and compliance review.
     *
     * @param sessionId The UUID of the parent [com.fieldiq.domain.NegotiationSession].
     * @return All events for this session, ordered by [NegotiationEvent.createdAt] ascending.
     *   Empty list if no events exist (should not happen — session creation always logs an event).
     */
    fun findBySessionId(sessionId: UUID): List<NegotiationEvent>
}
