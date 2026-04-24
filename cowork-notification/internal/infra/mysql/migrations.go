package mysql

import (
	"context"

	"gorm.io/gorm"
)

func EnsureSchema(ctx context.Context, db *gorm.DB) error {
	return db.WithContext(ctx).Exec(`
CREATE TABLE IF NOT EXISTS tb_device_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    token VARCHAR(512) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_device_token_account_token (account_id, token),
    INDEX idx_tb_device_token_account_id (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
`).Error
}
