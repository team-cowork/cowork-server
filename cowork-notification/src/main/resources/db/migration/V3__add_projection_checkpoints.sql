CREATE TABLE tb_projection_checkpoints
(
    consumer_group VARCHAR(255) NOT NULL,
    topic_name     VARCHAR(255) NOT NULL,
    partition_id  INT          NOT NULL,
    next_offset   BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name, partition_id),
    INDEX idx_tb_projection_checkpoints_topic_partition (topic_name, partition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tb_projection_dead_letters
(
    consumer_group VARCHAR(255) NOT NULL,
    topic_name     VARCHAR(255) NOT NULL,
    partition_id  INT          NOT NULL,
    message_offset BIGINT      NOT NULL,
    event_key     VARBINARY(512) NULL,
    payload       MEDIUMBLOB   NOT NULL,
    reason        TEXT         NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name, partition_id, message_offset),
    INDEX idx_tb_projection_dead_letters_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
