package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents an adult user of FieldIQ — a parent, coach, or team manager.
 *
 * **COPPA Compliance:** This table intentionally stores ONLY adult contact information.
 * No child PII (names, ages, photos) is stored here. Child names appear exclusively
 * in [TeamMember.playerName] for roster display purposes. This separation is a
 * deliberate COPPA compliance measure.
 *
 * Users authenticate via passwordless OTP (phone SMS or email magic link). At least
 * one of [phone] or [email] must be present for authentication, though the schema
 * allows both to be null to support pre-registration flows where a manager adds
 * a parent before the parent has logged in.
 *
 * A single user can be a member of multiple teams across multiple organizations,
 * with different roles on each team (e.g., manager on one team, parent on another).
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property phone Phone number in E.164 format (e.g., "+12025551234"). Used for
 *   SMS OTP authentication. Unique across the instance — one phone = one account.
 * @property email Email address for magic-link authentication. Unique across the
 *   instance. Either [phone] or [email] (or both) may be set.
 * @property displayName User's preferred display name shown in team rosters and
 *   notifications (e.g., "Sarah Johnson"). Optional; the app can fall back to
 *   phone/email for display if not set.
 * @property createdAt Timestamp of user creation, set once and never updated.
 * @see TeamMember for the team memberships and roles held by this user.
 * @see EventResponse for this user's RSVP responses to team events.
 */
@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(unique = true)
    val phone: String? = null,

    @Column(unique = true)
    val email: String? = null,

    @Column(name = "display_name")
    val displayName: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
