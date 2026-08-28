CREATE TABLE tb_team_role_command_operations
(
    operation_id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key                VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    team_id                        BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    actor_id                       BIGINT       NOT NULL COMMENT 'cowork-user의 account id',
    command_type                   VARCHAR(20)  NOT NULL,
    request_hash                   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                         VARCHAR(20)  NOT NULL,
    result_role_json               LONGTEXT     NULL,
    error_code                     VARCHAR(100) NULL,
    error_message                  VARCHAR(1000) NULL,
    expected_projection_key        VARCHAR(512) NULL,
    expected_projection_occurred_at DATETIME(6) NULL,
    expected_projection_deleted    BOOLEAN      NULL,
    created_at                     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_tb_team_role_command_operations PRIMARY KEY (operation_id),
    CONSTRAINT uq_tb_team_role_command_operations_actor_key UNIQUE (actor_id, idempotency_key),
    CONSTRAINT ck_tb_team_role_command_operations_type CHECK (
        command_type IN ('CREATE', 'UPDATE', 'DELETE', 'ASSIGN', 'REVOKE')
    ),
    CONSTRAINT ck_tb_team_role_command_operations_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')
    ),
    INDEX idx_tb_team_role_command_operations_team_created (team_id, created_at),
    INDEX idx_tb_team_role_command_operations_processing (status, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
