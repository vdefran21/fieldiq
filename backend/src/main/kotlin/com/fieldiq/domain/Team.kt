package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

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
