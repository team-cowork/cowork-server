package domain

import "time"

type RefreshToken struct {
	ID         int64     `gorm:"primaryKey;autoIncrement;column:id"`
	UserID     int64     `gorm:"index;column:user_id;not null"`
	TokenHash  string    `gorm:"uniqueIndex;size:512;column:token_hash;not null"`
	DeviceInfo *string   `gorm:"size:255;column:device_info"`
	Email      string    `gorm:"size:255;column:email;not null;default:''"`
	GsmRole    string    `gorm:"size:30;column:gsm_role;not null;default:''"`
	ExpiresAt  time.Time `gorm:"index;column:expires_at;not null"`
	CreatedAt  time.Time `gorm:"column:created_at;autoCreateTime:nano"`
}

func (RefreshToken) TableName() string {
	return "tb_refresh_tokens"
}
