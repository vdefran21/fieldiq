package com.fieldiq.repository

import com.fieldiq.domain.Organization
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [Organization] entities.
 *
 * Provides standard CRUD operations plus slug-based lookup. Organizations are
 * the top-level entity in the hierarchy, so this repository is primarily used
 * during onboarding (creating orgs) and when resolving public negotiation URLs
 * that reference an org by slug.
 *
 * @see Organization for the entity managed by this repository.
 */
interface OrganizationRepository : JpaRepository<Organization, UUID> {

    /**
     * Finds an organization by its URL-safe slug.
     *
     * Used when resolving public-facing negotiation URLs and during org lookup
     * by human-readable identifier. The slug has a UNIQUE constraint in the
     * database, so this returns at most one result.
     *
     * @param slug The URL-safe organization identifier (e.g., "bethesda-fire").
     * @return The matching [Organization], or null if no org has this slug.
     */
    fun findBySlug(slug: String): Organization?
}
