-- DataGSM webhook 이벤트 멱등 처리용 테이블
-- DataGSM은 최대 3회 재시도(중복 전달 가능)하므로 처리한 event id를 기록해 중복 처리를 방지한다.
CREATE TABLE IF NOT EXISTS tb_processed_events (
    event_id   VARCHAR(255) NOT NULL,
    event_type VARCHAR(64)  NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    KEY idx_tb_processed_events_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
