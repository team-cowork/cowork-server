ALTER TABLE tb_projection_checkpoints
    ADD COLUMN topic_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER partition_id;

ALTER TABLE tb_projection_dead_letters
    ADD COLUMN topic_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER partition_id;
