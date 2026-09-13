package mysql

import (
	"context"
	"encoding/json"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/delivery"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type deliveryRow struct {
	ID             int64      `gorm:"column:id;primaryKey;autoIncrement"`
	EventID        string     `gorm:"column:event_id"`
	DeviceTokenID  int64      `gorm:"column:device_token_id"`
	Token          string     `gorm:"column:token"`
	Title          string     `gorm:"column:title"`
	Body           string     `gorm:"column:body"`
	DataJSON       []byte     `gorm:"column:data_json"`
	Status         string     `gorm:"column:status"`
	AttemptCount   int        `gorm:"column:attempt_count"`
	NextAttemptAt  *time.Time `gorm:"column:next_attempt_at"`
	LastErrorClass *string    `gorm:"column:last_error_class"`
	CreatedAt      time.Time  `gorm:"column:created_at"`
	UpdatedAt      time.Time  `gorm:"column:updated_at"`
}

func (deliveryRow) TableName() string { return "tb_notification_delivery_retry" }

var terminalStatuses = map[string]bool{
	string(delivery.StatusSuccess):     true,
	string(delivery.StatusInvalid):     true,
	string(delivery.StatusQuarantined): true,
	string(delivery.StatusCancelled):   true,
}

type DeliveryRepository struct {
	db *gorm.DB
}

func NewDeliveryRepository(db *gorm.DB) *DeliveryRepository {
	return &DeliveryRepository{db: db}
}

func (r *DeliveryRepository) ResumeOrCreate(
	ctx context.Context,
	eventID string,
	targets []delivery.TargetToken,
	title, body string,
	data map[string]string,
) ([]delivery.TargetToken, error) {
	if len(targets) == 0 {
		return nil, nil
	}

	dataJSON, err := json.Marshal(data)
	if err != nil {
		return nil, err
	}

	deviceTokenIDs := make([]int64, len(targets))
	for i, t := range targets {
		deviceTokenIDs[i] = t.DeviceTokenID
	}

	var toSend []delivery.TargetToken
	err = r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var existing []deliveryRow
		if err := tx.
			Where("event_id = ? AND device_token_id IN ?", eventID, deviceTokenIDs).
			Find(&existing).Error; err != nil {
			return err
		}
		existingStatus := make(map[int64]string, len(existing))
		for _, row := range existing {
			existingStatus[row.DeviceTokenID] = row.Status
		}

		now := time.Now()
		newRows := make([]deliveryRow, 0, len(targets))
		for _, t := range targets {
			status, alreadyTracked := existingStatus[t.DeviceTokenID]
			if alreadyTracked {
				if terminalStatuses[status] {
					continue
				}
				toSend = append(toSend, t)
				continue
			}
			newRows = append(newRows, deliveryRow{
				EventID:       eventID,
				DeviceTokenID: t.DeviceTokenID,
				Token:         t.Token,
				Title:         title,
				Body:          body,
				DataJSON:      dataJSON,
				Status:        string(delivery.StatusPendingRetry),
				AttemptCount:  0,
				NextAttemptAt: &now,
			})
			toSend = append(toSend, t)
		}
		if len(newRows) == 0 {
			return nil
		}
		return tx.Create(&newRows).Error
	})
	if err != nil {
		return nil, err
	}
	return toSend, nil
}

func (r *DeliveryRepository) FinalizeSuccess(ctx context.Context, eventID string, deviceTokenID int64) error {
	return r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("event_id = ? AND device_token_id = ?", eventID, deviceTokenID).
		Update("status", string(delivery.StatusSuccess)).Error
}

func (r *DeliveryRepository) FinalizeInvalid(ctx context.Context, eventID string, deviceTokenID int64) error {
	return r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("event_id = ? AND device_token_id = ?", eventID, deviceTokenID).
		Update("status", string(delivery.StatusInvalid)).Error
}

func (r *DeliveryRepository) FinalizeFailure(
	ctx context.Context,
	eventID string,
	deviceTokenID int64,
	errClass delivery.ErrorClass,
	now time.Time,
) (delivery.Status, error) {
	var status delivery.Status
	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var row deliveryRow
		if err := tx.Clauses(clause.Locking{Strength: clause.LockingStrengthUpdate}).
			Where("event_id = ? AND device_token_id = ?", eventID, deviceTokenID).
			Take(&row).Error; err != nil {
			return err
		}

		var attempts int
		var nextAttemptAt *time.Time
		status, attempts, nextAttemptAt = delivery.NextAfterFailure(errClass, row.AttemptCount, now)
		errClassStr := string(errClass)
		return tx.Model(&deliveryRow{}).Where("id = ?", row.ID).Updates(map[string]any{
			"status":           string(status),
			"attempt_count":    attempts,
			"next_attempt_at":  nextAttemptAt,
			"last_error_class": errClassStr,
		}).Error
	})
	return status, err
}

func (r *DeliveryRepository) ClaimDue(ctx context.Context, limit int, now time.Time) ([]delivery.Record, error) {
	var claimed []deliveryRow
	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var due []deliveryRow
		if err := tx.Clauses(clause.Locking{
			Strength: clause.LockingStrengthUpdate,
			Options:  clause.LockingOptionsSkipLocked,
		}).
			Where("status = ? AND next_attempt_at <= ?", string(delivery.StatusPendingRetry), now).
			Order("next_attempt_at ASC").
			Limit(limit).
			Find(&due).Error; err != nil {
			return err
		}
		if len(due) == 0 {
			return nil
		}
		ids := make([]int64, len(due))
		for i, row := range due {
			ids[i] = row.ID
		}
		if err := tx.Model(&deliveryRow{}).Where("id IN ?", ids).
			Update("status", string(delivery.StatusInProgress)).Error; err != nil {
			return err
		}
		claimed = due
		return nil
	})
	if err != nil {
		return nil, err
	}

	records := make([]delivery.Record, 0, len(claimed))
	for _, row := range claimed {
		rec, err := toRecord(row)
		if err != nil {
			return nil, err
		}
		records = append(records, rec)
	}
	return records, nil
}

func (r *DeliveryRepository) FinalizeCancelled(ctx context.Context, id int64) error {
	return r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("id = ?", id).
		Update("status", string(delivery.StatusCancelled)).Error
}

func (r *DeliveryRepository) ReclaimStale(ctx context.Context, staleThreshold time.Duration, now time.Time) (int64, error) {
	cutoff := now.Add(-staleThreshold)
	result := r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("status = ? AND updated_at < ?", string(delivery.StatusInProgress), cutoff).
		Updates(map[string]any{
			"status":          string(delivery.StatusPendingRetry),
			"next_attempt_at": now,
		})
	return result.RowsAffected, result.Error
}

func (r *DeliveryRepository) CountPending(ctx context.Context) (int64, error) {
	var count int64
	err := r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("status IN ?", []string{string(delivery.StatusPendingRetry), string(delivery.StatusInProgress)}).
		Count(&count).Error
	return count, err
}

func toRecord(row deliveryRow) (delivery.Record, error) {
	var data map[string]string
	if len(row.DataJSON) > 0 {
		if err := json.Unmarshal(row.DataJSON, &data); err != nil {
			return delivery.Record{}, err
		}
	}
	var errClass *delivery.ErrorClass
	if row.LastErrorClass != nil {
		ec := delivery.ErrorClass(*row.LastErrorClass)
		errClass = &ec
	}
	return delivery.Record{
		ID:             row.ID,
		EventID:        row.EventID,
		DeviceTokenID:  row.DeviceTokenID,
		Token:          row.Token,
		Title:          row.Title,
		Body:           row.Body,
		Data:           data,
		Status:         delivery.Status(row.Status),
		AttemptCount:   row.AttemptCount,
		NextAttemptAt:  row.NextAttemptAt,
		LastErrorClass: errClass,
		CreatedAt:      row.CreatedAt,
		UpdatedAt:      row.UpdatedAt,
	}, nil
}
