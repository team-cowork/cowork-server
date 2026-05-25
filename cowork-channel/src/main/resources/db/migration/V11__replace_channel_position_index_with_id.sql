ALTER TABLE tb_channels
    DROP INDEX idx_tb_channels_team_position,
    ADD INDEX idx_tb_channels_team_position_id (team_id, position, id);
