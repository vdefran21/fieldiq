package com.fieldiq.api.dto

import com.fieldiq.domain.User
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * User profile data returned in API responses.
 *
 * Contains only adult contact info (COPPA compliance — no child data here).
 * Child names appear exclusively in [TeamMemberDto.playerName].
 *
 * Corresponds to TypeScript interface: `UserDto` in `shared/types/index.ts`.
 * Corresponds to Kotlin entity: [com.fieldiq.domain.User].
 *
 * @property id UUID of the user.
 * @property phone Phone number in E.164 format. Present if user registered via SMS.
 * @property email Email address. Present if user registered via email.
 * @property displayName User's preferred display name (e.g., "Sarah Johnson").
 */
data class UserDto(
    val id: String,
    val phone: String? = null,
    val email: String? = null,
    val displayName: String? = null,
) {
    companion object {
        /**
         * Converts a [User] entity to a [UserDto] response object.
         *
         * @param user The user entity to convert.
         * @return A [UserDto] with all fields mapped.
         */
        fun from(user: User): UserDto = UserDto(
            id = user.id.toString(),
            phone = user.phone,
            email = user.email,
            displayName = user.displayName,
        )
    }
}

/**
 * Request body for `POST /users/me/devices`.
 *
 * Registers an Expo push notification token for the authenticated user.
 * Called on app launch to ensure the server has the current push token.
 *
 * Corresponds to TypeScript interface: `RegisterDeviceRequest` in `shared/types/index.ts`.
 *
 * @property expoPushToken Expo push token (e.g., "ExponentPushToken[...]").
 * @property platform Device platform: "ios" or "android". iOS only in Phase 1.
 */
data class RegisterDeviceRequest(
    @field:NotBlank(message = "Expo push token is required")
    val expoPushToken: String,

    @field:NotBlank(message = "Platform is required")
    @field:Pattern(regexp = "ios|android", message = "Platform must be 'ios' or 'android'")
    val platform: String,
)
