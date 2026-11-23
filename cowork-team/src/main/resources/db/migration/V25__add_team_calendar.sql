CREATE TABLE tb_team_calendar_events
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    team_id              BIGINT        NOT NULL COMMENT 'tb_teams.id',
    created_by           BIGINT        NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    title                VARCHAR(200)  NOT NULL,
    description          VARCHAR(2000) DEFAULT NULL,
    start_at             DATETIME(6)   NOT NULL COMMENT '첫 발생분 시작 시각',
    end_at               DATETIME(6)   NOT NULL COMMENT '첫 발생분 종료 시각',
    all_day              BOOLEAN       NOT NULL DEFAULT FALSE,
    time_zone            VARCHAR(64)   NOT NULL DEFAULT 'Asia/Seoul' COMMENT 'IANA 시간대. 반복은 이 시간대의 현지 시각으로 계산한다',
    recurrence_frequency VARCHAR(10)   DEFAULT NULL COMMENT 'DAILY, WEEKLY, MONTHLY. NULL이면 반복하지 않는다',
    recurrence_interval  INT           NOT NULL DEFAULT 1,
    recurrence_by_day    VARCHAR(64)   DEFAULT NULL COMMENT 'WEEKLY의 요일 목록. 쉼표로 구분한 MONDAY 형식',
    recurrence_until     DATETIME(6)   DEFAULT NULL COMMENT '마지막으로 허용하는 발생분 시작 시각',
    recurrence_count     INT           DEFAULT NULL COMMENT '첫 발생분부터 센 전체 발생 수',
    reminder_minutes     VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '시작 몇 분 전에 알릴지. 쉼표로 구분한 분 단위 값',
    range_start_at       DATETIME(6)   NOT NULL COMMENT '예외를 포함한 시리즈 전체의 시작 시각',
    range_end_at         DATETIME(6)   DEFAULT NULL COMMENT '예외를 포함한 시리즈 전체의 종료 시각. NULL이면 끝이 없다',
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_team_calendar_events_team_id_range (team_id, range_start_at),
    INDEX idx_tb_team_calendar_events_reminder (reminder_minutes, range_start_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_calendar_event_attendees
(
    event_id BIGINT NOT NULL COMMENT 'tb_team_calendar_events.id',
    user_id  BIGINT NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    PRIMARY KEY (event_id, user_id),
    CONSTRAINT fk_tb_team_calendar_event_attendees_event
        FOREIGN KEY (event_id) REFERENCES tb_team_calendar_events (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_calendar_event_exceptions
(
    event_id          BIGINT        NOT NULL COMMENT 'tb_team_calendar_events.id',
    original_start_at DATETIME(6)   NOT NULL COMMENT '반복 규칙이 만든 원래 시작 시각',
    cancelled         BOOLEAN       NOT NULL DEFAULT FALSE,
    title             VARCHAR(200)  DEFAULT NULL,
    description       VARCHAR(2000) DEFAULT NULL,
    start_at          DATETIME(6)   DEFAULT NULL,
    end_at            DATETIME(6)   DEFAULT NULL,
    PRIMARY KEY (event_id, original_start_at),
    CONSTRAINT fk_tb_team_calendar_event_exceptions_event
        FOREIGN KEY (event_id) REFERENCES tb_team_calendar_events (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_calendar_reminder_deliveries
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    event_id          BIGINT      NOT NULL COMMENT 'tb_team_calendar_events.id',
    original_start_at DATETIME(6) NOT NULL COMMENT '알린 발생분의 원래 시작 시각',
    start_at          DATETIME(6) NOT NULL COMMENT '알린 시점의 실제 시작 시각. 발생분을 옮기면 새 시각으로 다시 알린다',
    minutes_before    INT         NOT NULL,
    delivered_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tb_team_calendar_reminder_deliveries_occurrence
        (event_id, original_start_at, start_at, minutes_before),
    INDEX idx_tb_team_calendar_reminder_deliveries_delivered_at (delivered_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_calendar_reminder_cursor
(
    id         BIGINT      NOT NULL,
    checked_at DATETIME(6) NOT NULL COMMENT '리마인더를 마지막으로 확인한 시각',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_team_calendar_reminder_cursor (id, checked_at) VALUES (1, CURRENT_TIMESTAMP(6));

CREATE TABLE tb_team_calendar_project_event_projections
(
    event_id           BIGINT        NOT NULL COMMENT 'cowork-project.tb_project_calendar_events.id',
    project_id         BIGINT        NOT NULL COMMENT 'cowork-project.tb_projects.id',
    team_id            BIGINT        NOT NULL COMMENT 'tb_teams.id',
    created_by         BIGINT        NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    title              VARCHAR(200)  NOT NULL,
    description        VARCHAR(2000) DEFAULT NULL,
    start_at           DATETIME(6)   NOT NULL COMMENT '첫 발생분 시작 시각',
    end_at             DATETIME(6)   NOT NULL COMMENT '첫 발생분 종료 시각',
    all_day            BOOLEAN       NOT NULL DEFAULT FALSE,
    time_zone          VARCHAR(64)   NOT NULL COMMENT 'IANA 시간대',
    recurrence_json    TEXT          DEFAULT NULL COMMENT 'project.calendar-event.state.v1 recurrence JSON',
    exceptions_json    TEXT          NOT NULL COMMENT 'project.calendar-event.state.v1 exceptions JSON 배열',
    category_id        BIGINT        DEFAULT NULL COMMENT 'cowork-project.tb_project_calendar_categories.id',
    category_name      VARCHAR(50)   DEFAULT NULL,
    category_color_hex VARCHAR(7)    DEFAULT NULL,
    attendee_user_ids  TEXT          NOT NULL COMMENT 'cowork-user.tb_accounts.id 목록. 오름차순, 쉼표로 구분',
    reminder_minutes   VARCHAR(32)   NOT NULL DEFAULT '',
    range_start_at     DATETIME(6)   NOT NULL COMMENT '예외를 포함한 시리즈 전체의 시작 시각',
    range_end_at       DATETIME(6)   DEFAULT NULL COMMENT '예외를 포함한 시리즈 전체의 종료 시각. NULL이면 끝이 없다',
    deleted            BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '삭제 이력. 늦게 온 옛 버전을 무시한다',
    source_occurred_at DATETIME(6)   NOT NULL COMMENT 'project.calendar-event.state.v1 occurredAt',
    PRIMARY KEY (event_id),
    INDEX idx_tb_team_calendar_project_event_projections_team_range (team_id, deleted, range_start_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_calendar_project_reader_states
(
    project_id         BIGINT      NOT NULL COMMENT 'cowork-project.tb_projects.id',
    team_id            BIGINT      NOT NULL COMMENT 'tb_teams.id',
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '프로젝트 삭제 이력',
    source_occurred_at DATETIME(6) NOT NULL COMMENT 'project.readable-user.state.v1 occurredAt',
    PRIMARY KEY (project_id),
    INDEX idx_tb_team_calendar_project_reader_states_team_id (team_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_calendar_project_readers
(
    project_id BIGINT NOT NULL COMMENT 'tb_team_calendar_project_reader_states.project_id',
    user_id    BIGINT NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    PRIMARY KEY (project_id, user_id),
    INDEX idx_tb_team_calendar_project_readers_user_id (user_id),
    CONSTRAINT fk_tb_team_calendar_project_readers_state
        FOREIGN KEY (project_id) REFERENCES tb_team_calendar_project_reader_states (project_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
