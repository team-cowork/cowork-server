package token

import "time"

type Platform string

const (
	PlatformIOS     Platform = "IOS"
	PlatformAndroid Platform = "ANDROID"
	PlatformWeb     Platform = "WEB"
)

type DeviceToken struct {
	ID        int64     `gorm:"primaryKey;autoIncrement;column:id"`
	AccountID int64     `gorm:"column:account_id;not null;index:idx_tb_device_token_account_id"`
	Token     string    `gorm:"column:token;size:512;not null;uniqueIndex:uq_tb_device_token_token"`
	Platform  Platform  `gorm:"column:platform;size:20;not null"`
	CreatedAt time.Time `gorm:"column:created_at;autoCreateTime"`
	UpdatedAt time.Time `gorm:"column:updated_at;autoUpdateTime"`
}

type RegistrationResult struct {
	DeviceTokenID     int64
	PreviousAccountID int64
	Reassigned        bool
}

func (DeviceToken) TableName() string { return "tb_device_token" }
