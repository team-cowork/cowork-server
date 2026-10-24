CREATE TABLE tb_initial_resource_access_ledger (
    resource_type VARCHAR(16) NOT NULL CHECK (resource_type IN ('CHANNEL', 'PROJECT')),
    resource_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_type, resource_id)
);
COMMENT ON TABLE tb_initial_resource_access_ledger IS
    '초기 권한 처리 및 이후 수동 변경 기록. projection 재구성 때 삭제하지 않는 권한 원본 데이터';
COMMENT ON COLUMN tb_initial_resource_access_ledger.resource_id IS
    'CHANNEL: cowork-channel.tb_channels.id; PROJECT: cowork-project.tb_projects.id';
