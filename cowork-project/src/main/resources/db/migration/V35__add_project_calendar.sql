CREATE TABLE tb_project_calendar_categories
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL COMMENT 'tb_projects.id',
    team_id    BIGINT      NOT NULL COMMENT 'tb_projects.team_id 비정규화',
    name       VARCHAR(50) NOT NULL,
    color_hex  VARCHAR(7)  NOT NULL COMMENT '#RRGGBB',
    created_by BIGINT      NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tb_project_calendar_categories_project_id_name (project_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_calendar_events
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    project_id           BIGINT        NOT NULL COMMENT 'tb_projects.id',
    team_id              BIGINT        NOT NULL COMMENT 'tb_projects.team_id 비정규화',
    created_by           BIGINT        NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    title                VARCHAR(200)  NOT NULL,
    description          VARCHAR(2000) DEFAULT NULL,
    start_at             DATETIME(6)   NOT NULL COMMENT '첫 발생분 시작 시각',
    end_at               DATETIME(6)   NOT NULL COMMENT '첫 발생분 종료 시각',
    all_day              BOOLEAN       NOT NULL DEFAULT FALSE,
    time_zone            VARCHAR(64)   NOT NULL DEFAULT 'Asia/Seoul' COMMENT 'IANA 시간대. 반복은 이 시간대의 현지 시각으로 계산한다',
    category_id          BIGINT        DEFAULT NULL COMMENT 'tb_project_calendar_categories.id',
    recurrence_frequency VARCHAR(10)   DEFAULT NULL COMMENT 'DAILY, WEEKLY, MONTHLY. NULL이면 반복하지 않는다',
    recurrence_interval  INT           NOT NULL DEFAULT 1,
    recurrence_by_day    VARCHAR(64)   DEFAULT NULL COMMENT 'WEEKLY의 요일 목록. 쉼표로 구분한 MONDAY 형식',
    recurrence_until     DATETIME(6)   DEFAULT NULL COMMENT '마지막으로 허용하는 발생분 시작 시각',
    recurrence_count     INT           DEFAULT NULL COMMENT '첫 발생분부터 센 전체 발생 수',
    reminder_minutes     VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '시작 몇 분 전에 알릴지. 쉼표로 구분한 분 단위 값',
    range_start_at       DATETIME(6)   NOT NULL COMMENT '예외를 포함한 시리즈 전체의 시작 시각',
    range_end_at         DATETIME(6)   DEFAULT NULL COMMENT '예외를 포함한 시리즈 전체의 종료 시각. NULL이면 끝이 없다',
    deleted              BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '삭제 이력. 상태 토픽 snapshot이 삭제를 다시 발행한다',
    state_occurred_at    DATETIME(6)   NOT NULL COMMENT 'project.calendar-event.state.v1 원본 변경 버전',
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_project_calendar_events_project_id_range (project_id, deleted, range_start_at),
    INDEX idx_tb_project_calendar_events_category_id (category_id),
    INDEX idx_tb_project_calendar_events_reminder (deleted, reminder_minutes, range_start_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_calendar_event_attendees
(
    event_id BIGINT NOT NULL COMMENT 'tb_project_calendar_events.id',
    user_id  BIGINT NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    PRIMARY KEY (event_id, user_id),
    CONSTRAINT fk_tb_project_calendar_event_attendees_event
        FOREIGN KEY (event_id) REFERENCES tb_project_calendar_events (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_calendar_event_exceptions
(
    event_id          BIGINT        NOT NULL COMMENT 'tb_project_calendar_events.id',
    original_start_at DATETIME(6)   NOT NULL COMMENT '반복 규칙이 만든 원래 시작 시각',
    cancelled         BOOLEAN       NOT NULL DEFAULT FALSE,
    title             VARCHAR(200)  DEFAULT NULL,
    description       VARCHAR(2000) DEFAULT NULL,
    start_at          DATETIME(6)   DEFAULT NULL,
    end_at            DATETIME(6)   DEFAULT NULL,
    PRIMARY KEY (event_id, original_start_at),
    CONSTRAINT fk_tb_project_calendar_event_exceptions_event
        FOREIGN KEY (event_id) REFERENCES tb_project_calendar_events (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_calendar_reminder_deliveries
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    event_id          BIGINT      NOT NULL COMMENT 'tb_project_calendar_events.id',
    original_start_at DATETIME(6) NOT NULL COMMENT '알린 발생분의 원래 시작 시각',
    start_at          DATETIME(6) NOT NULL COMMENT '알린 시점의 실제 시작 시각. 발생분을 옮기면 새 시각으로 다시 알린다',
    minutes_before    INT         NOT NULL,
    delivered_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tb_project_calendar_reminder_deliveries_occurrence
        (event_id, original_start_at, start_at, minutes_before),
    INDEX idx_tb_project_calendar_reminder_deliveries_delivered_at (delivered_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_calendar_reminder_cursor
(
    id         BIGINT      NOT NULL,
    checked_at DATETIME(6) NOT NULL COMMENT '리마인더를 마지막으로 확인한 시각',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_project_calendar_reminder_cursor (id, checked_at) VALUES (1, CURRENT_TIMESTAMP(6));

CREATE TABLE tb_project_readable_user_states
(
    project_id        BIGINT      NOT NULL COMMENT 'tb_projects.id',
    team_id           BIGINT      NOT NULL COMMENT 'tb_projects.team_id 비정규화',
    user_ids          TEXT        NOT NULL COMMENT '프로젝트를 읽을 수 있는 account id. 오름차순, 쉼표로 구분',
    deleted           BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '프로젝트 삭제 이력',
    state_occurred_at DATETIME(6) NOT NULL COMMENT 'project.readable-user.state.v1 원본 변경 버전',
    PRIMARY KEY (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
