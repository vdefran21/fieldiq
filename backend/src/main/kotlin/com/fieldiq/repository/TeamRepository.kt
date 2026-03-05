package com.fieldiq.repository

import com.fieldiq.domain.Team
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [Team] entities.
 *
 * Provides standard CRUD operations plus organization-scoped queries. All team
 * data access in controllers should be preceded by a [com.fieldiq.service.TeamAccessGuard]
 * check to enforce multi-tenancy — this repository does not enforce access control.
 *
 * @see Team for the entity managed by this repository.
 * @see com.fieldiq.service.TeamAccessGuard for access control enforcement.
 */
interface TeamRepository : JpaRepository<Team, UUID> {

    /**
     * Finds all teams belonging to a specific organization.
     *
     * Used in org admin views to list all teams under a club. Does not filter
     * by the requesting user's membership — callers must check access separately.
     *
     * @param orgId The UUID of the parent [Organization].
     * @return All teams under the given organization, empty list if none exist.
     */
    fun findByOrgId(orgId: UUID): List<Team>
}
