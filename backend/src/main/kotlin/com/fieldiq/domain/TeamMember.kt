package com.fieldiq.domain

import jakarta.persistence.*
import java.util.UUID

/**
 * Join table linking [User] to [Team] with a specific role.
 *
 * This is the core multi-tenancy boundary in FieldIQ. Every data access operation
 * is scoped through team membership — [TeamAccessGuard] checks this table before
 * allowing any team resource access. The unique constraint on (teamId, userId)
 * prevents duplicate memberships.
 *
 * Roles determine what actions a user can take:
 * - **manager**: Full control — can initiate negotiations, manage roster, create events.
 *   Every team must have at least one manager (the team creator).
 * - **coach**: Can create events and view all team data, but cannot initiate negotiations.
 * - **parent**: Can view schedule, RSVP to events, declare availability. The [playerName]
 *   field stores their child's name for roster display (COPPA: child name is stored
 *   here, not in the [User] table).
 *
 * Soft-delete via [isActive]: deactivated members lose access but their historical
 * data (RSVPs, availability) is preserved for audit purposes.
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property teamId Foreign key to the [Team] this membership belongs to.
 * @property userId Foreign key to the [User] who holds this membership.
 * @property role The user's role on this team. One of: "manager", "coach", "parent".
 *   Enforced by a CHECK constraint in the database.
 * @property playerName The child's name associated with this parent-team relationship
 *   (e.g., "Jake Johnson"). Only meaningful when [role] is "parent". This is the
 *   ONLY place child names are stored (COPPA compliance).
 * @property isActive Whether this membership is currently active. Inactive members
 *   cannot access team resources but their data is retained.
 * @see TeamAccessGuard for the service that enforces access based on this table.
 * @see User for the user entity referenced by [userId].
 * @see Team for the team entity referenced by [teamId].
 */
@Entity
@Table(name = "team_members")
data class TeamMember(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "team_id", nullable = false)
    val teamId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(nullable = false)
    val role: String,

    @Column(name = "player_name")
    val playerName: String? = null,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
)
