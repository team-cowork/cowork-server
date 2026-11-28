package com.cowork.roadmap.global.team.repository;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.cowork.roadmap.global.team.event.RolePolicyState;
import com.cowork.roadmap.global.team.event.TeamAccessState;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RoadmapPolicyRepository {
    private final JdbcTemplate jdbcTemplate;

    public void apply(String key, RolePolicyState state) {
        jdbcTemplate.update("""
                INSERT INTO tb_roadmap_role_policies
                  (policy_key, team_id, kind, role_id, account_id, is_default, permissions, deleted, source_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  is_default = IF(VALUES(source_version) > source_version, VALUES(is_default), is_default),
                  permissions = IF(VALUES(source_version) > source_version, VALUES(permissions), permissions),
                  deleted = IF(VALUES(source_version) > source_version, VALUES(deleted),
                              deleted OR (VALUES(source_version) = source_version AND VALUES(deleted))),
                  source_version = GREATEST(source_version, VALUES(source_version))
                """,
                key,
                0,
                state.kind(),
                state.roleId(),
                state.accountId(),
                state.isDefault(),
                state.permissions(),
                state.deleted(),
                Timestamp.from(state.occurredAt()));
    }

    public boolean accountAllows(long userId, String permission) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM tb_roadmap_role_policies r
                    JOIN tb_roadmap_role_policies a ON a.team_id = r.team_id AND a.role_id = r.role_id
                    WHERE r.team_id = 0 AND r.kind = 'ROLE' AND NOT r.deleted AND NOT r.is_default
                      AND a.kind = 'ASSIGNMENT' AND a.account_id = ? AND NOT a.deleted
                      AND JSON_CONTAINS(r.permissions, 'true', ?)
                )
                """, Boolean.class, userId, "$." + permission));
    }

    public void applyTeamAccess(TeamAccessState state) {
        jdbcTemplate.update(
                """
                        INSERT INTO tb_team_access_projections
                            (team_id, account_id, manage_roadmaps, source_membership_version, source_authorization_version, source_occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            manage_roadmaps = CASE
                                WHEN VALUES(source_occurred_at) > source_occurred_at THEN VALUES(manage_roadmaps)
                                WHEN VALUES(source_occurred_at) = source_occurred_at THEN
                                    manage_roadmaps AND VALUES(manage_roadmaps)
                                    AND source_membership_version = VALUES(source_membership_version)
                                    AND (source_authorization_version <=> VALUES(source_authorization_version))
                                ELSE manage_roadmaps END,
                            source_membership_version = IF(VALUES(source_occurred_at) > source_occurred_at, VALUES(source_membership_version), source_membership_version),
                            source_authorization_version = IF(VALUES(source_occurred_at) > source_occurred_at, VALUES(source_authorization_version), source_authorization_version),
                            source_occurred_at = GREATEST(source_occurred_at, VALUES(source_occurred_at))
                        """,
                state.teamId(),
                state.accountId(),
                state.manageRoadmaps(),
                Timestamp.from(state.membershipVersion()),
                state.authorizationVersion() == null ? null : Timestamp.from(state.authorizationVersion()),
                Timestamp.from(state.occurredAt()));
    }

    public boolean canManageRoadmaps(long teamId, long userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM tb_team_access_projections a
                    JOIN tb_team_member_projections m ON m.team_id = a.team_id AND m.user_id = a.account_id
                        AND m.active AND m.authorization_version = a.source_authorization_version
                    WHERE a.team_id = ? AND a.account_id = ? AND a.manage_roadmaps
                )
                """, Boolean.class, teamId, userId));
    }
}
