package com.cowork.roadmap.global.team.repository;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.cowork.roadmap.global.team.event.RolePolicyState;

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
                state.teamId(),
                state.kind(),
                state.roleId(),
                state.accountId(),
                state.isDefault(),
                state.permissions(),
                state.deleted(),
                Timestamp.from(state.occurredAt()));
    }

    public boolean allows(long teamId, long userId, String permission) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM tb_roadmap_role_policies r
                            WHERE r.kind = 'ROLE' AND r.team_id = ? AND NOT r.deleted
                              AND (r.team_id <> 0 OR NOT r.is_default)
                              AND JSON_CONTAINS(r.permissions, 'true', ?) AND (
                                (r.team_id <> 0 AND r.is_default) OR EXISTS (
                                  SELECT 1 FROM tb_roadmap_role_policies a
                                  WHERE a.team_id = r.team_id AND a.kind = 'ASSIGNMENT' AND a.role_id = r.role_id
                                    AND a.account_id = ? AND NOT a.deleted AND NOT EXISTS (
                                      SELECT 1 FROM tb_roadmap_role_policies f WHERE f.team_id = a.team_id
                                        AND f.kind = 'MEMBER' AND f.account_id = a.account_id AND f.source_version >= a.source_version
                                    )
                                )
                            )
                        )
                        """,
                Boolean.class,
                teamId,
                "$." + permission,
                userId));
    }
}
