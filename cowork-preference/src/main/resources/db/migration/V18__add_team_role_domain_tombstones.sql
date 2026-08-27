CREATE TABLE tb_team_role_domain_tombstones
(
    team_id            BIGINT      PRIMARY KEY,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE tb_team_role_domain_tombstones IS
    '삭제된 팀의 사용자 정의 역할 도메인이 command로 재생성되지 않도록 유지하는 영구 fence';
COMMENT ON COLUMN tb_team_role_domain_tombstones.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_team_role_domain_tombstones.source_occurred_at IS
    'cowork-team OWNER 멤버 삭제 상태의 occurredAt';
