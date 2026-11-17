package com.cowork.project.support

import com.cowork.project.domain.membership.TeamRolePermissionReader
import com.cowork.project.domain.project.entity.ProjectAccessProjection
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.Optional

val policyVersion: Instant = Instant.parse("2026-11-06T00:00:00Z")

fun projectPolicy(projectId: Long, userId: Long) = ProjectAccessProjection(
    "$projectId:$userId", projectId, userId, true, true, policyVersion,
    canManage = true, canReorder = true, canReadPrivate = true,
    canBoardRead = true, canBoardWrite = true, sourceAuthorizationVersion = policyVersion,
)

fun projectPolicies(userId: Long, vararg projectIds: Long) = mockk<ProjectAccessProjectionRepository> {
    every { findById(any()) } returns Optional.empty()
    projectIds.forEach { projectId ->
        every { findById("$projectId:$userId") } returns Optional.of(projectPolicy(projectId, userId))
    }
    every { findAllByUserIdAndProjectIdIn(userId, any()) } answers {
        secondArg<Collection<Long>>().filter { it in projectIds }.map { projectPolicy(it, userId) }
    }
}

fun teamPolicies(userId: Long) = mockk<TeamRolePermissionReader> {
    every { hasPermission(any(), any(), any()) } returns false
    every { hasPermission(100L, userId, "manage_github") } returns true
    every { hasPermission(100L, userId, "manage_projects") } returns true
    every { hasPermission(100L, userId, "administrator") } returns true
}
