CREATE TABLE tb_roadmaps
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    title            VARCHAR(150)  NOT NULL,
    description      VARCHAR(1000) NULL,
    category         VARCHAR(50)   NOT NULL COMMENT '종류/전공/포지션 (예: Flutter, Server, Web)',
    scope            VARCHAR(20)   NOT NULL COMMENT 'GLOBAL | TEAM | PROJECT',
    owner_team_id    BIGINT        NULL COMMENT 'cowork-team의 tb_teams.id (커스텀 로드맵 소유 팀)',
    owner_project_id BIGINT        NULL COMMENT 'cowork-project의 tb_projects.id (PROJECT 스코프)',
    created_by       BIGINT        NULL COMMENT '생성자 사용자 ID',
    last_modified_by BIGINT        NULL COMMENT '최종 수정자 사용자 ID',
    created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_roadmaps_category_scope (category, scope),
    INDEX idx_tb_roadmaps_owner_team_id (owner_team_id),
    INDEX idx_tb_roadmaps_owner_project_id (owner_project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_roadmap_nodes
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    roadmap_id       BIGINT       NOT NULL,
    parent_id        BIGINT       NULL COMMENT '상위 노드 ID (NULL이면 루트). 서브트리 삭제는 애플리케이션에서 처리',
    title            VARCHAR(200) NOT NULL COMMENT '노드/문서 제목',
    content          LONGTEXT     NULL COMMENT '번역된 본문 (마크다운)',
    source_url       VARCHAR(500) NULL COMMENT '원본 문서 URL',
    source_title     VARCHAR(300) NULL COMMENT '원본 문서 제목',
    position         INT          NOT NULL DEFAULT 0 COMMENT '동일 부모 내 정렬 순서',
    created_by       BIGINT       NULL,
    last_modified_by BIGINT       NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_roadmap_nodes_roadmap_id_parent_id (roadmap_id, parent_id, position),
    CONSTRAINT fk_tb_roadmap_nodes_roadmap FOREIGN KEY (roadmap_id) REFERENCES tb_roadmaps (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_roadmap_node_references
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    node_id    BIGINT       NOT NULL,
    title      VARCHAR(200) NOT NULL COMMENT '관련 자료 제목',
    url        VARCHAR(500) NOT NULL COMMENT '관련 자료 링크',
    position   INT          NOT NULL DEFAULT 0 COMMENT '노드 내 정렬 순서',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_roadmap_node_references_node_id (node_id, position),
    CONSTRAINT fk_tb_roadmap_node_references_node FOREIGN KEY (node_id) REFERENCES tb_roadmap_nodes (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_roadmap_assignments
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    roadmap_id       BIGINT      NOT NULL,
    node_id          BIGINT      NULL COMMENT 'NULL이면 로드맵 전체, 값이 있으면 특정 노드(서브트리) 할당',
    scope            VARCHAR(20) NOT NULL COMMENT 'TEAM | PROJECT (할당 맥락)',
    team_id          BIGINT      NULL COMMENT 'cowork-team의 tb_teams.id',
    project_id       BIGINT      NULL COMMENT 'cowork-project의 tb_projects.id',
    assignee_user_id BIGINT      NOT NULL COMMENT '온보딩 대상 사용자 ID',
    assigned_by      BIGINT      NOT NULL COMMENT '과제 출제자 사용자 ID',
    status           VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED' COMMENT 'ASSIGNED | IN_PROGRESS | DONE',
    due_date         DATETIME(6) NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_roadmap_assignments_assignee_user_id (assignee_user_id, status),
    INDEX idx_tb_roadmap_assignments_roadmap_id (roadmap_id),
    UNIQUE uq_tb_roadmap_assignments_roadmap_node_assignee (roadmap_id, node_id, assignee_user_id),
    CONSTRAINT fk_tb_roadmap_assignments_roadmap FOREIGN KEY (roadmap_id) REFERENCES tb_roadmaps (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
