package com.fieldiq.repository

import com.fieldiq.domain.UserDevice
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [UserDevice] entities.
 *
 * Provides queries for push notification device management. Devices are registered
 * when the mobile app launches and sends its Expo push token. The notification
 * dispatch worker uses [findByUserId] to look up all devices for a user when
 * sending push notifications.
 *
 * @see UserDevice for the entity managed by this repository.
 * @see com.fieldiq.service.UserDeviceService for the device registration logic.
 */
interface UserDeviceRepository : JpaRepository<UserDevice, UUID> {

    /**
     * Finds all registered devices for a user.
     *
     * Used by the notification dispatch agent worker to send push notifications
     * to all of a user's devices. A user may have multiple devices (e.g., phone
     * and tablet).
     *
     * @param userId The UUID of the user.
     * @return All active devices for the user, empty list if none registered.
     */
    fun findByUserId(userId: UUID): List<UserDevice>

    /**
     * Finds a specific device by user ID and push token.
     *
     * Used during device registration to implement upsert behavior — if the
     * device is already registered, update [UserDevice.lastSeenAt] instead of
     * creating a duplicate. The UNIQUE constraint on (user_id, expo_push_token)
     * enforces this at the DB level as well.
     *
     * @param userId The UUID of the user.
     * @param expoPushToken The Expo push token to look up.
     * @return The matching [UserDevice], or null if not found.
     */
    fun findByUserIdAndExpoPushToken(userId: UUID, expoPushToken: String): UserDevice?
}
