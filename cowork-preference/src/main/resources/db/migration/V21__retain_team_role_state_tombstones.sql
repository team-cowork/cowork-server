CREATE TABLE tb_team_role_tombstones
(
    team_id           BIGINT      NOT NULL,
    role_id           BIGINT      NOT NULL,
    state_occurred_at TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_team_role_tombstones PRIMARY KEY (team_id, role_id)
);

COMMENT ON TABLE tb_team_role_tombstones IS
    'preference.team-role.changed full snapshot에서 ROLE_DELETED를 재발행하기 위한 영구 tombstone';
COMMENT ON COLUMN tb_team_role_tombstones.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_team_role_tombstones.role_id IS
    '삭제된 cowork-preference의 tb_team_role_definitions.id';

CREATE TABLE tb_team_role_assignment_tombstones
(
    team_id           BIGINT      NOT NULL,
    account_id        BIGINT      NOT NULL,
    role_id           BIGINT      NOT NULL,
    state_occurred_at TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_team_role_assignment_tombstones PRIMARY KEY (team_id, account_id, role_id)
);

COMMENT ON TABLE tb_team_role_assignment_tombstones IS
    'preference.team-role.changed full snapshot에서 ASSIGNMENT_DELETED를 재발행하기 위한 영구 tombstone';
COMMENT ON COLUMN tb_team_role_assignment_tombstones.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_team_role_assignment_tombstones.account_id IS 'cowork-user의 account id';
COMMENT ON COLUMN tb_team_role_assignment_tombstones.role_id IS
    '삭제될 수 있는 cowork-preference의 tb_team_role_definitions.id';

CREATE INDEX idx_tb_team_role_assignment_tombstones_role
    ON tb_team_role_assignment_tombstones (team_id, role_id, account_id);

CREATE TABLE tb_team_role_member_fences
(
    team_id           BIGINT      NOT NULL,
    account_id        BIGINT      NOT NULL,
    state_occurred_at TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_team_role_member_fences PRIMARY KEY (team_id, account_id)
);

COMMENT ON TABLE tb_team_role_member_fences IS
    '멤버 탈퇴 이전 역할 할당이 snapshot generation 교체 후 부활하지 않도록 하는 영구 fence';
COMMENT ON COLUMN tb_team_role_member_fences.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_team_role_member_fences.account_id IS 'cowork-user의 account id';

CREATE OR REPLACE FUNCTION set_team_role_updated_at_monotonic()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at := GREATEST(
        clock_timestamp(),
        OLD.updated_at + INTERVAL '1 microsecond'
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_tb_team_role_definitions_updated_at ON tb_team_role_definitions;

CREATE TRIGGER trg_tb_team_role_definitions_updated_at
    BEFORE UPDATE ON tb_team_role_definitions
    FOR EACH ROW EXECUTE FUNCTION set_team_role_updated_at_monotonic();
