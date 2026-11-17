CREATE TABLE tb_project_github_boards
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    project_id     BIGINT       NOT NULL COMMENT 'tb_projects.id',
    team_id        BIGINT       NOT NULL COMMENT 'tb_projects.team_id 비정규화. 조직 소유 검사에 사용',
    owner_login    VARCHAR(100) NOT NULL COMMENT 'GitHub Project를 소유한 조직 login. 소문자로 저장',
    project_number INT          NOT NULL COMMENT 'GitHub Project 번호',
    created_by     BIGINT       NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_tb_project_github_boards PRIMARY KEY (id),
    CONSTRAINT uq_tb_project_github_boards_project_owner_number UNIQUE (project_id, owner_login, project_number),
    INDEX idx_tb_project_github_boards_team_id (team_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_project_projections
(
    id                 VARCHAR(120) NOT NULL COMMENT '"owner/projectNumber"',
    owner              VARCHAR(100) NOT NULL,
    project_number     INT          NOT NULL,
    node_id            VARCHAR(100) NULL COMMENT 'GitHub ProjectV2 node ID',
    title              VARCHAR(500) NOT NULL,
    url                VARCHAR(512) NOT NULL,
    closed             BOOLEAN      NOT NULL DEFAULT FALSE,
    access_error       VARCHAR(20)  NULL COMMENT 'NOT_FOUND, FORBIDDEN',
    fields_json        MEDIUMTEXT   NOT NULL COMMENT 'github.project.state.v1 fields',
    views_json         MEDIUMTEXT   NOT NULL COMMENT 'github.project.state.v1 views',
    item_ids_json      MEDIUMTEXT   NOT NULL COMMENT 'GitHub 위치 순서의 보관되지 않은 item node ID 목록',
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_project_projections PRIMARY KEY (id),
    INDEX idx_tb_github_project_projections_node_id (node_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_project_item_projections
(
    id                 VARCHAR(100) NOT NULL COMMENT 'GitHub ProjectV2Item node ID',
    project_node_id    VARCHAR(100) NOT NULL COMMENT 'GitHub ProjectV2 node ID',
    owner              VARCHAR(100) NOT NULL,
    payload_json       MEDIUMTEXT   NOT NULL COMMENT 'github.project-item.state.v1 레코드',
    archived           BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_project_item_projections PRIMARY KEY (id),
    INDEX idx_tb_github_project_item_projections_project (project_node_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_project_item_write_operations
(
    operation_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    command_type    VARCHAR(20)  NOT NULL COMMENT 'MOVE_ITEM, REORDER_ITEM',
    board_id        BIGINT       NOT NULL COMMENT 'tb_project_github_boards.id',
    project_id      BIGINT       NOT NULL COMMENT 'tb_projects.id',
    item_id         VARCHAR(100) NULL COMMENT 'GitHub ProjectV2Item node ID. 아직 item이 없는 커맨드는 NULL',
    requested_by    BIGINT       NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING, SUCCEEDED, FAILED',
    error_code      VARCHAR(100) NULL,
    error_message   VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_tb_github_project_item_write_operations PRIMARY KEY (operation_id),
    CONSTRAINT ck_tb_github_project_item_write_operations_command_type CHECK (command_type IN ('MOVE_ITEM', 'REORDER_ITEM')),
    CONSTRAINT ck_tb_github_project_item_write_operations_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    INDEX idx_tb_github_project_item_write_operations_board_id (board_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
