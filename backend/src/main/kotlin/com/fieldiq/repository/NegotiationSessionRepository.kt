package com.fieldiq.repository

import com.fieldiq.domain.NegotiationSession
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [NegotiationSession] entities.
 *
 * Provides queries for the cross-team negotiation protocol — FieldIQ's core IP.
 * Sessions are looked up by invite token (during the join handshake), by team
 * (for a manager's negotiation dashboard), and by status (for cleanup jobs
 * that expire stale sessions).
 *
 * @see NegotiationSession for the entity managed by this repository.
 * @see NegotiationProposalRepository for the proposals within a session.
 */
interface NegotiationSessionRepository : JpaRepository<NegotiationSession, UUID> {

    /**
     * Finds a negotiation session by its single-use invite token.
     *
     * Called during the join handshake when Manager B uses the invite link.
     * The invite token has a UNIQUE constraint, so this returns at most one result.
     * After a successful join, the token is consumed (set to null) to prevent reuse.
     *
     * @param inviteToken The bearer token from the invite link.
     * @return The matching [NegotiationSession], or null if the token is invalid,
     *   already consumed, or doesn't exist.
     */
    fun findByInviteToken(inviteToken: String): NegotiationSession?

    /**
     * Finds all negotiation sessions initiated by a specific team.
     *
     * Used for the manager's negotiation dashboard in the mobile app — shows
     * all negotiations this team has started, regardless of status. Includes
     * active, confirmed, failed, and cancelled sessions.
     *
     * @param teamId The UUID of the initiating team.
     * @return All sessions initiated by this team, empty list if none.
     */
    fun findByInitiatorTeamId(teamId: UUID): List<NegotiationSession>

    /**
     * Finds all negotiation sessions in a given status.
     *
     * Used by cleanup/expiration jobs to find sessions that need automated
     * state transitions (e.g., move "pending_response" sessions past their
     * TTL to "failed"). Also useful for monitoring dashboards.
     *
     * @param status The status to filter by (e.g., "pending_response", "proposing").
     * @return All sessions in the given status, empty list if none.
     */
    fun findByStatus(status: String): List<NegotiationSession>
}
