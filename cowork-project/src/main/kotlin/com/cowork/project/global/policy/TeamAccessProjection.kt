package com.cowork.project.global.policy

import com.cowork.project.global.consumer.TeamAccessState
import com.cowork.project.global.projection.ProjectionReadinessGate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class TeamAccessProjection(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val projectionReadinessGate: ProjectionReadinessGate,
) {
    fun apply(event: TeamAccessState) {
        jdbcTemplate.update(
            """
            INSERT INTO tb_team_access_projections
                (team_id, account_id, allowed_permissions, source_membership_version, source_authorization_version, source_occurred_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                allowed_permissions = CASE
                    WHEN VALUES(source_occurred_at) > source_occurred_at THEN VALUES(allowed_permissions)
                    WHEN VALUES(source_occurred_at) = source_occurred_at AND
                        (allowed_permissions <> VALUES(allowed_permissions)
                         OR source_membership_version <> VALUES(source_membership_version)
                         OR NOT (source_authorization_version <=> VALUES(source_authorization_version))) THEN JSON_ARRAY()
                    ELSE allowed_permissions END,
                source_membership_version = IF(VALUES(source_occurred_at) > source_occurred_at, VALUES(source_membership_version), source_membership_version),
                source_authorization_version = IF(VALUES(source_occurred_at) > source_occurred_at, VALUES(source_authorization_version), source_authorization_version),
                source_occurred_at = GREATEST(source_occurred_at, VALUES(source_occurred_at))
            """.trimIndent(),
            event.teamId,
            event.accountId,
            objectMapper.writeValueAsString(event.allowedPermissions.sorted()),
            LocalDateTime.ofInstant(event.membershipVersion, ZoneOffset.UTC),
            event.authorizationVersion?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) },
            LocalDateTime.ofInstant(event.occurredAt, ZoneOffset.UTC),
        )
    }

    fun permissions(teamId: Long, userId: Long): Set<String> {
        projectionReadinessGate.requireReady()
        return jdbcTemplate.query(
            """
            SELECT a.allowed_permissions FROM tb_team_access_projections a
            JOIN tb_team_memberships m ON m.team_id = a.team_id AND m.user_id = a.account_id
                AND m.active AND m.authorization_version = a.source_authorization_version
            WHERE a.team_id = ? AND a.account_id = ?
            """.trimIndent(),
            { row, _ -> objectMapper.readValue<Set<String>>(row.getString(1)) },
            teamId,
            userId,
        ).singleOrNull().orEmpty()
    }

    fun hasPermission(teamId: Long, userId: Long, permission: String): Boolean =
        permission in permissions(teamId, userId)
}
