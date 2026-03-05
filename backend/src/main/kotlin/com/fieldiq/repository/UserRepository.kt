package com.fieldiq.repository

import com.fieldiq.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [User] entities.
 *
 * Provides standard CRUD operations plus lookup by authentication identifiers
 * (phone number, email). These lookups are the primary entry point during the
 * OTP authentication flow — when a user requests an OTP, we look up or create
 * their User record by phone/email.
 *
 * Both [findByPhone] and [findByEmail] leverage UNIQUE indexes for O(1) lookups.
 *
 * @see User for the entity managed by this repository.
 */
interface UserRepository : JpaRepository<User, UUID> {

    /**
     * Finds a user by their phone number.
     *
     * Used during SMS OTP authentication to resolve the user account. Phone numbers
     * should be stored in E.164 format (e.g., "+12025551234") for consistent matching.
     *
     * @param phone The phone number to search for, in E.164 format.
     * @return The matching [User], or null if no user has this phone number.
     */
    fun findByPhone(phone: String): User?

    /**
     * Finds a user by their email address.
     *
     * Used during email magic-link authentication to resolve the user account.
     * Email matching is case-sensitive at the database level — callers should
     * normalize to lowercase before querying.
     *
     * @param email The email address to search for.
     * @return The matching [User], or null if no user has this email.
     */
    fun findByEmail(email: String): User?
}
