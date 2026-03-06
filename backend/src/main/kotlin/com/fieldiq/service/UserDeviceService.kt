package com.fieldiq.service

import com.fieldiq.api.dto.RegisterDeviceRequest
import com.fieldiq.domain.UserDevice
import com.fieldiq.repository.UserDeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Business logic for registering mobile devices for push notifications.
 *
 * When the React Native Expo app launches, it sends its Expo push token to the
 * backend. This service stores or updates the device record so the notification
 * dispatch agent worker can send push notifications to the user's devices.
 *
 * **Upsert behavior:** If the same user + push token combination already exists,
 * the existing record's [UserDevice.lastSeenAt] is updated rather than creating
 * a duplicate. This handles app reinstalls and token refreshes gracefully.
 *
 * **Database impact:** Writes to the `user_devices` table. The UNIQUE constraint
 * on (user_id, expo_push_token) prevents duplicate entries at the DB level.
 *
 * @property userDeviceRepository Repository for [UserDevice] entity CRUD and queries.
 * @see com.fieldiq.api.UserController for the REST endpoint that delegates to this service.
 */
@Service
class UserDeviceService(
    private val userDeviceRepository: UserDeviceRepository,
) {
    private val logger = LoggerFactory.getLogger(UserDeviceService::class.java)

    /**
     * Registers a device for push notifications, using upsert semantics.
     *
     * If the user has already registered this exact push token, updates the
     * [UserDevice.lastSeenAt] timestamp. Otherwise, creates a new device record.
     * This handles the common case where the app re-registers its token on each
     * launch without creating duplicate entries.
     *
     * @param userId The UUID of the authenticated user registering the device.
     * @param request The device details: Expo push token and platform (ios/android).
     * @return The [UserDevice] entity (either newly created or updated).
     */
    @Transactional
    fun registerDevice(userId: UUID, request: RegisterDeviceRequest): UserDevice {
        val existing = userDeviceRepository.findByUserIdAndExpoPushToken(userId, request.expoPushToken)

        return if (existing != null) {
            val updated = existing.copy(
                lastSeenAt = Instant.now(),
                platform = request.platform,
            )
            userDeviceRepository.save(updated).also {
                logger.debug("Device updated for user {}: token={}", userId, request.expoPushToken.take(20))
            }
        } else {
            userDeviceRepository.save(
                UserDevice(
                    userId = userId,
                    expoPushToken = request.expoPushToken,
                    platform = request.platform,
                )
            ).also {
                logger.info("New device registered for user {}: platform={}", userId, request.platform)
            }
        }
    }
}
