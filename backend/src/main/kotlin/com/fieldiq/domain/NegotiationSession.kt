package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "negotiation_sessions")
data class NegotiationSession(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "initiator_team_id", nullable = false)
    val initiatorTeamId: UUID,

    @Column(name = "initiator_instance", nullable = false)
    val initiatorInstance: String,

    @Column(name = "initiator_manager")
    val initiatorManager: UUID? = null,

    @Column(name = "responder_team_id")
    val responderTeamId: UUID? = null,

    @Column(name = "responder_instance")
    val responderInstance: String? = null,

    @Column(name = "responder_external_id")
    val responderExternalId: String? = null,

    @Column(nullable = false)
    val status: String = "pending_response",

    @Column(name = "requested_date_range_start")
    val requestedDateRangeStart: LocalDate? = null,

    @Column(name = "requested_date_range_end")
    val requestedDateRangeEnd: LocalDate? = null,

    @Column(name = "requested_duration_minutes", nullable = false)
    val requestedDurationMinutes: Int = 90,

    @Column(name = "agreed_starts_at")
    val agreedStartsAt: Instant? = null,

    @Column(name = "agreed_location")
    val agreedLocation: String? = null,

    @Column(name = "invite_token", unique = true)
    val inviteToken: String? = null,

    @Column(name = "session_key_hash")
    val sessionKeyHash: String? = null,

    @Column(name = "max_rounds", nullable = false)
    val maxRounds: Int = 3,

    @Column(name = "current_round", nullable = false)
    val currentRound: Int = 0,

    @Column(name = "expires_at")
    val expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
