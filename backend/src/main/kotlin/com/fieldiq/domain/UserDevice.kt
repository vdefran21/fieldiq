package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents a registered mobile device for push notifications.
 *
 * When the React Native Expo app launches, it obtains an Expo push token and
 * registers it with the backend via `POST /users/me/devices`. This token is used
 * by the notification dispatch agent worker to send push notifications (game
 * confirmations, RSVP reminders, negotiation updates).
 *
 * **Upsert behavior:** If a user re-registers the same push token (e.g., after
 * app reinstall), the existing record is updated rather than creating a duplicate.
 * The UNIQUE constraint on (user_id, expo_push_token) enforces this.
 *
 * **Staleness:** The [lastSeenAt] field is updated on each registration to track
 * device activity. Devices not seen for 90+ days may be pruned in future cleanup jobs.
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property userId Foreign key to the [User] who owns this device. NOT NULL.
 * @property expoPushToken The Expo push notification token (e.g., "ExponentPushToken[xxxx]").
 *   Unique per user — one token per device per user.
 * @property platform Device platform: "ios" or "android". Enforced by CHECK constraint.
 *   Phase 1 targets iOS only, but Android support is tracked for Phase 2.
 * @property lastSeenAt Timestamp of the most recent device registration or heartbeat.
 *   Updated on each `POST /users/me/devices` call.
 * @property createdAt Timestamp of initial device registration. Immutable.
 * @see User for the user who owns this device.
 */
@Entity
@Table(name = "user_devices")
data class UserDevice(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "expo_push_token", nullable = false)
    val expoPushToken: String,

    @Column(nullable = false)
    val platform: String,

    @Column(name = "last_seen_at", nullable = false)
    val lastSeenAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
