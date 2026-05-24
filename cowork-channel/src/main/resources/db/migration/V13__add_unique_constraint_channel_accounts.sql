ALTER TABLE tb_channel_accounts
    ADD CONSTRAINT uq_tb_channel_accounts_channel_provider_identifier
        UNIQUE (channel_id, provider, account_identifier);