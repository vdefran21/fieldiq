package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "availability_windows")
data class AvailabilityWindow(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "team_id", nullable = false)
    val teamId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "day_of_week")
    val dayOfWeek: Short? = null,

    @Column(name = "specific_date")
    val specificDate: LocalDate? = null,

    @Column(name = "start_time", nullable = false)
    val startTime: LocalTime,

    @Column(name = "end_time", nullable = false)
    val endTime: LocalTime,

    @Column(name = "window_type", nullable = false)
    val windowType: String, // 'available', 'unavailable'

    @Column(nullable = false)
    val source: String = "manual", // 'manual', 'google_cal', 'apple_cal'

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
