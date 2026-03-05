package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "events")
data class Event(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "team_id", nullable = false)
    val teamId: UUID,

    @Column(name = "event_type", nullable = false)
    val eventType: String, // 'game', 'practice', 'tournament', 'other'

    val title: String? = null,

    val location: String? = null,

    @Column(name = "location_notes")
    val locationNotes: String? = null,

    @Column(name = "starts_at")
    val startsAt: Instant? = null,

    @Column(name = "ends_at")
    val endsAt: Instant? = null,

    @Column(nullable = false)
    val status: String = "scheduled", // 'draft', 'scheduled', 'cancelled', 'completed'

    @Column(name = "opponent_team_id")
    val opponentTeamId: UUID? = null,

    @Column(name = "opponent_name")
    val opponentName: String? = null,

    @Column(name = "opponent_external_ref")
    val opponentExternalRef: String? = null,

    @Column(name = "negotiation_id")
    val negotiationId: UUID? = null,

    @Column(name = "created_by")
    val createdBy: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
