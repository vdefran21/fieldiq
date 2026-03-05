package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "negotiation_proposals")
data class NegotiationProposal(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,

    @Column(name = "proposed_by", nullable = false)
    val proposedBy: String, // 'initiator', 'responder'

    @Column(name = "round_number", nullable = false)
    val roundNumber: Int = 1,

    @Column(nullable = false, columnDefinition = "jsonb")
    val slots: String, // JSON string

    @Column(name = "schema_version", nullable = false)
    val schemaVersion: Int = 1,

    @Column(name = "response_status")
    val responseStatus: String = "pending", // 'pending', 'accepted', 'rejected', 'countered'

    @Column(name = "rejection_reason")
    val rejectionReason: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
