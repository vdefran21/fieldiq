package com.fieldiq.domain

import jakarta.persistence.*
import java.util.UUID

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
    val role: String, // 'manager', 'coach', 'parent'

    @Column(name = "player_name")
    val playerName: String? = null,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
)
