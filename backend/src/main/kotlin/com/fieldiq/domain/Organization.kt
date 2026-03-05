package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents a club, rec league, or other organizational entity that owns teams.
 *
 * Organizations are the top-level billing and administrative unit in FieldIQ.
 * In Phase 1, they primarily serve as a grouping mechanism for teams. A single
 * organization might represent "Bethesda Soccer Club" which has multiple age-group
 * teams (U10, U12, U14, etc.).
 *
 * The [slug] is used in public-facing negotiation URLs and must be unique across
 * the entire instance. It provides a human-readable identifier for cross-team
 * communication (e.g., "bethesda-fire" rather than a UUID).
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property name Display name of the organization (e.g., "Bethesda Fire SC").
 * @property slug URL-safe unique identifier used in negotiation URLs and public references.
 *   Must be lowercase, alphanumeric with hyphens (e.g., "bethesda-fire").
 * @property timezone IANA timezone identifier used for scheduling calculations. Defaults to
 *   Eastern time since Phase 1 targets DMV (DC/Maryland/Virginia) youth soccer.
 * @property createdAt Timestamp of organization creation, set once and never updated.
 * @see Team for the teams that belong to this organization.
 */
@Entity
@Table(name = "organizations")
data class Organization(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false, unique = true)
    val slug: String,

    @Column(nullable = false)
    val timezone: String = "America/New_York",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
