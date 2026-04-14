package domain

import "time"

type User struct {
	ID         int64      `gorm:"primaryKey;autoIncrement;column:id"`
	Email      string     `gorm:"uniqueIndex;size:255;column:email;not null"`
	Name       string     `gorm:"size:50;column:name;not null"`
	Sex        string     `gorm:"size:10;column:sex;not null"`
	Grade      *int8      `gorm:"column:grade"`
	Class      *int8      `gorm:"column:class"`
	ClassNum   *int8      `gorm:"column:class_num"`
	Major      string     `gorm:"size:20;column:major;not null"`
	Specialty  *string    `gorm:"size:255;column:specialty"`
	GithubID   *string    `gorm:"uniqueIndex;size:100;column:github_id"`
	GsmRole    string     `gorm:"size:30;column:gsm_role;not null;default:GENERAL_STUDENT"`
	SystemRole string     `gorm:"size:20;column:system_role;not null;default:MEMBER"`
	CreatedAt  time.Time  `gorm:"column:created_at;autoCreateTime:nano"`
	UpdatedAt  time.Time  `gorm:"column:updated_at;autoUpdateTime:nano"`
}

func (User) TableName() string {
	return "tb_users"
}
