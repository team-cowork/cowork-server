package domain

import (
	"errors"
	"time"
)

var (
	ErrRefreshTokenExpired       = errors.New("refresh token expired")
	ErrRefreshTokenOwnerMismatch = errors.New("refresh token does not belong to user")
)

type RefreshToken struct {
	ID           int64     `gorm:"primaryKey;autoIncrement;column:id"`
	UserID       int64     `gorm:"index:idx_tb_refresh_tokens_user_id_expires_at,priority:1;column:user_id;not null"`
	TokenHash    string    `gorm:"uniqueIndex;type:char(64) CHARACTER SET ascii COLLATE ascii_bin;column:token_hash;not null"`
	DeviceInfo   *string   `gorm:"size:255;column:device_info"`
	Email        string    `gorm:"size:255;column:email;not null;default:''"`
	GsmRole      string    `gorm:"size:30;column:gsm_role;not null;default:''"`
	PlatformRole string    `gorm:"size:20;column:platform_role;not null;default:'MEMBER'"`
	ExpiresAt    time.Time `gorm:"index;index:idx_tb_refresh_tokens_user_id_expires_at,priority:2;column:expires_at;not null"`
	CreatedAt    time.Time `gorm:"column:created_at;autoCreateTime:nano"`
}

func (RefreshToken) TableName() string {
	return "tb_refresh_tokens"
}

func RefreshTokenUnexpired(expiresAt, at time.Time) bool {
	return expiresAt.After(at)
}
