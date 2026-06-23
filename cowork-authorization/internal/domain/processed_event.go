package domain

import "time"

type ProcessedEvent struct {
	EventID   string    `gorm:"primaryKey;size:255;column:event_id"`
	EventType string    `gorm:"size:64;column:event_type;not null"`
	CreatedAt time.Time `gorm:"column:created_at;autoCreateTime:nano"`
}

func (ProcessedEvent) TableName() string {
	return "tb_processed_events"
}
