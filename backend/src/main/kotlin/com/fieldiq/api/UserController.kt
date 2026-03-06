package com.fieldiq.api

import com.fieldiq.api.dto.RegisterDeviceRequest
import com.fieldiq.api.dto.UserDto
import com.fieldiq.repository.UserRepository
import com.fieldiq.security.authenticatedUserId
import com.fieldiq.service.UserDeviceService
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for the authenticated user's profile and device management.
 *
 * Provides endpoints scoped to the currently authenticated user (via `me` convention).
 * All endpoints require JWT authentication. The user ID is extracted from the JWT
 * via [authenticatedUserId].
 *
 * Endpoints:
 * - `GET /users/me` — Get the authenticated user's profile
 * - `POST /users/me/devices` — Register a device for push notifications
 *
 * @property userRepository Repository for [com.fieldiq.domain.User] lookups.
 * @property userDeviceService Business logic for device registration.
 * @see UserDeviceService for push token registration logic.
 */
@RestController
@RequestMapping("/users/me")
class UserController(
    private val userRepository: UserRepository,
    private val userDeviceService: UserDeviceService,
) {

    /**
     * Returns the authenticated user's profile.
     *
     * Used by the mobile app on login to display the user's name, phone, and email.
     * The user is looked up by the UUID extracted from the JWT.
     *
     * @return 200 OK with the [UserDto] containing the user's profile data.
     * @throws EntityNotFoundException If the user record is missing (should not happen
     *   in normal flow since the JWT was issued for a valid user).
     */
    @GetMapping
    fun getMe(): ResponseEntity<UserDto> {
        val userId = authenticatedUserId()
        val user = userRepository.findById(userId)
            .orElseThrow { EntityNotFoundException("User $userId not found") }
        return ResponseEntity.ok(UserDto.from(user))
    }

    /**
     * Registers a device for push notifications.
     *
     * Called by the Expo app on launch to register its push token. Uses upsert
     * semantics: if the same token is already registered for this user, updates
     * the lastSeenAt timestamp instead of creating a duplicate.
     *
     * @param request The device details: Expo push token and platform (ios/android).
     * @return 200 OK on successful registration.
     */
    @PostMapping("/devices")
    fun registerDevice(@Valid @RequestBody request: RegisterDeviceRequest): ResponseEntity<Void> {
        val userId = authenticatedUserId()
        userDeviceService.registerDevice(userId, request)
        return ResponseEntity.ok().build()
    }
}
