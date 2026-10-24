ALTER TABLE tb_team_members
    ADD COLUMN nickname VARCHAR(50) NULL COMMENT '팀 안에서 표시할 닉네임; NULL이면 계정 닉네임을 사용' AFTER role;

ALTER TABLE tb_team_member_event_states
    ADD COLUMN nickname VARCHAR(50) NULL AFTER team_name;

CREATE TABLE tb_team_setting_projections
(
    team_id                  BIGINT      NOT NULL COMMENT 'cowork-preference 팀 설정 state의 teamId(cowork-team의 tb_teams.id)',
    nickname_format_enforced BOOLEAN     NOT NULL DEFAULT FALSE,
    nickname_format_example  TEXT        NULL,
    source_occurred_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (team_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

ALTER TABLE tb_team_role_projections ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE tb_team_member_event_states
    ADD COLUMN authorization_version DATETIME(6) NULL COMMENT '가입·탈퇴·기본 역할 변경 버전',
    ADD COLUMN authorization_state_version DATETIME(6) NULL COMMENT '권한 버전 계산이 적용된 원본 이벤트 버전';

UPDATE tb_team_member_event_states
SET authorization_version = state_occurred_at,
    authorization_state_version = state_occurred_at;
