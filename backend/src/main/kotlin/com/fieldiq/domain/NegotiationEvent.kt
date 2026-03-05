package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "negotiation_events")
data class NegotiationEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,

    @Column(name = "event_type", nullable = false)
    val eventType: String,

    val actor: String? = null, // 'initiator', 'responder', 'system'

    @Column(columnDefinition = "jsonb")
    val payload: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
