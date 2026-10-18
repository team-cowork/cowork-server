ALTER TABLE tb_user_identity_command_inbox
    ADD COLUMN hash_version SMALLINT NOT NULL DEFAULT 1 COMMENT '1 = Elixir(Jason) 직렬화 해시, 2 = System.Text.Json 정규 직렬화 해시' AFTER command_hash;
