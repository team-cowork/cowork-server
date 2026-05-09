package mysql

import (
	"context"
	"fmt"

	"gorm.io/gorm"
)

func EnsureSchema(ctx context.Context, db *gorm.DB) error {
	statements := []string{
		`CREATE TABLE IF NOT EXISTS tb_refresh_tokens (
			id BIGINT NOT NULL AUTO_INCREMENT,
			user_id BIGINT NOT NULL,
			token_hash VARCHAR(512) NOT NULL,
			device_info VARCHAR(255) NULL,
			email VARCHAR(255) NOT NULL DEFAULT '',
			gsm_role VARCHAR(30) NOT NULL DEFAULT '',
			expires_at DATETIME(6) NOT NULL,
			created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
			PRIMARY KEY (id),
			UNIQUE KEY uq_tb_refresh_tokens_hash (token_hash),
			KEY idx_tb_refresh_tokens_user_id (user_id),
			KEY idx_tb_refresh_tokens_expires_at (expires_at)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`,
	}

	for _, stmt := range statements {
		if err := db.WithContext(ctx).Exec(stmt).Error; err != nil {
			return fmt.Errorf("failed to ensure authorization schema: %w", err)
		}
	}

	return nil
}
