package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents a youth sports team within an organization.
 *
 * Teams are the primary unit of scheduling in FieldIQ. Each team has members
 * (parents, coaches, managers) who declare availability, and the team participates
 * in cross-team negotiations to schedule games. A team belongs to exactly one
 * [Organization], identified by [orgId].
 *
 * In cross-instance negotiations, a team on a remote FieldIQ instance is referenced
 * by its UUID on that instance. The local instance stores the remote team's ID in
 * [NegotiationSession.responderExternalId] rather than creating a local Team record.
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property orgId Foreign key to the parent [Organization]. Nullable to support
 *   standalone teams not yet assigned to an org (edge case in onboarding).
 * @property name Display name (e.g., "Bethesda Fire U12 Boys").
 * @property sport Sport type, defaults to "soccer" for Phase 1. Stored as a string
 *   rather than an enum to allow easy extension without migrations.
 * @property ageGroup Age division label (e.g., "U10", "U14"). Used for display
 *   and future matchmaking but not enforced by the schema.
 * @property season Season identifier (e.g., "Spring2026"). Allows teams to be
 *   scoped to a specific season for archival purposes.
 * @property externalId Optional identifier from an external system (e.g., a league
 *   registration system). Reserved for future data migration tooling.
 * @property createdAt Timestamp of team creation, set once and never updated.
 * @see Organization for the parent organizational entity.
 * @see TeamMember for the users associated with this team.
 */
@Entity
@Table(name = "teams")
data class Team(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "org_id")
    val orgId: UUID? = null,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val sport: String = "soccer",

    @Column(name = "age_group")
    val ageGroup: String? = null,

    val season: String? = null,

    @Column(name = "external_id")
    val externalId: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
