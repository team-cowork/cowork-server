package installation

import "time"

type Platform string

const (
	PlatformIOS     Platform = "IOS"
	PlatformAndroid Platform = "ANDROID"
	PlatformWeb     Platform = "WEB"
)

// Installation is one Firebase app installation registered to an account. FID is the
// Firebase installation ID FCM delivers to; it is unique across accounts so a single
// app installation never has two owners.
type Installation struct {
	ID        int64     `gorm:"primaryKey;autoIncrement;column:id"`
	AccountID int64     `gorm:"column:account_id;not null;index:idx_tb_device_installation_account_id"`
	FID       string    `gorm:"column:fid;size:64;not null;uniqueIndex:uq_tb_device_installation_fid"`
	Platform  Platform  `gorm:"column:platform;size:20;not null"`
	CreatedAt time.Time `gorm:"column:created_at;autoCreateTime"`
	UpdatedAt time.Time `gorm:"column:updated_at;autoUpdateTime"`
}

type RegistrationResult struct {
	InstallationID    int64
	PreviousAccountID int64
	Reassigned        bool
}

func (Installation) TableName() string { return "tb_device_installation" }
