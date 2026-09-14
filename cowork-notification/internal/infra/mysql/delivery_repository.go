package mysql

import (
	"context"
	crand "crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
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
	ClaimToken     *string    `gorm:"column:claim_token"`
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

// clearedContent is merged into every terminal-transition update so a delivered,
// dropped, or quarantined row no longer carries a copy of the notification's title,
// body, or data payload — the source message may since have been edited or deleted.
var clearedContent = map[string]any{
	"title":     "",
	"body":      "",
	"data_json": nil,
}

const purgeBatchSize = 1000

// newClaimToken issues an opaque fencing token for one IN_PROGRESS claim. Finalize*
// calls must echo it back; a mismatch means the row was reclaimed (stale worker
// timeout) or already finalized by a different attempt, so the caller must not
// overwrite whatever outcome that other attempt recorded.
func newClaimToken() string {
	var b [16]byte
	_, _ = crand.Read(b[:]) // crypto/rand.Read is documented to never fail on supported platforms
	return hex.EncodeToString(b[:])
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
		if err := tx.Clauses(clause.Locking{Strength: clause.LockingStrengthUpdate}).
			Where("event_id = ? AND device_token_id IN ?", eventID, deviceTokenIDs).
			Find(&existing).Error; err != nil {
			return err
		}
		existingByDeviceToken := make(map[int64]deliveryRow, len(existing))
		for _, row := range existing {
			existingByDeviceToken[row.DeviceTokenID] = row
		}

		now := time.Now()
		var newRows []deliveryRow
		for _, t := range targets {
			row, tracked := existingByDeviceToken[t.DeviceTokenID]
			if !tracked {
				claimToken := newClaimToken()
				newRows = append(newRows, deliveryRow{
					EventID:       eventID,
					DeviceTokenID: t.DeviceTokenID,
					Token:         t.Token,
					Title:         title,
					Body:          body,
					DataJSON:      dataJSON,
					Status:        string(delivery.StatusInProgress),
					ClaimToken:    &claimToken,
				})
				toSend = append(toSend, delivery.TargetToken{
					DeviceTokenID: t.DeviceTokenID,
					Token:         t.Token,
					ClaimToken:    claimToken,
				})
				continue
			}
			if terminalStatuses[row.Status] || row.Status == string(delivery.StatusInProgress) {
				// Terminal: already finished. IN_PROGRESS: the retry worker (or a
				// concurrent redelivery of the same message) is already attempting
				// it — never send a second copy on top of that.
				continue
			}
			// PENDING_RETRY: only resume if its backoff has elapsed, using the same
			// due condition as ClaimDue, so a redelivered message cannot jump its
			// backoff schedule.
			if row.NextAttemptAt != nil && row.NextAttemptAt.After(now) {
				continue
			}
			claimToken := newClaimToken()
			if err := tx.Model(&deliveryRow{}).Where("id = ?", row.ID).Updates(map[string]any{
				"status":      string(delivery.StatusInProgress),
				"claim_token": claimToken,
			}).Error; err != nil {
				return err
			}
			toSend = append(toSend, delivery.TargetToken{
				DeviceTokenID: t.DeviceTokenID,
				Token:         t.Token,
				ClaimToken:    claimToken,
			})
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

func (r *DeliveryRepository) FinalizeSuccess(ctx context.Context, eventID string, deviceTokenID int64, claimToken string) error {
	updates := map[string]any{"status": string(delivery.StatusSuccess), "claim_token": nil}
	for k, v := range clearedContent {
		updates[k] = v
	}
	result := r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("event_id = ? AND device_token_id = ? AND status = ? AND claim_token = ?",
			eventID, deviceTokenID, string(delivery.StatusInProgress), claimToken).
		Updates(updates)
	if result.Error != nil {
		return result.Error
	}
	warnIfClaimStale(result.RowsAffected, "success", eventID, deviceTokenID)
	return nil
}

func (r *DeliveryRepository) FinalizeInvalid(ctx context.Context, eventID string, deviceTokenID int64, claimToken string) error {
	updates := map[string]any{"status": string(delivery.StatusInvalid), "claim_token": nil}
	for k, v := range clearedContent {
		updates[k] = v
	}
	result := r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("event_id = ? AND device_token_id = ? AND status = ? AND claim_token = ?",
			eventID, deviceTokenID, string(delivery.StatusInProgress), claimToken).
		Updates(updates)
	if result.Error != nil {
		return result.Error
	}
	warnIfClaimStale(result.RowsAffected, "invalid", eventID, deviceTokenID)
	return nil
}

func (r *DeliveryRepository) FinalizeFailure(
	ctx context.Context,
	eventID string,
	deviceTokenID int64,
	claimToken string,
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
		if row.Status != string(delivery.StatusInProgress) || row.ClaimToken == nil || *row.ClaimToken != claimToken {
			warnIfClaimStale(0, "failure", eventID, deviceTokenID)
			status = delivery.Status(row.Status)
			return nil
		}

		var attempts int
		var nextAttemptAt *time.Time
		status, attempts, nextAttemptAt = delivery.NextAfterFailure(errClass, row.AttemptCount, now)
		errClassStr := string(errClass)
		updates := map[string]any{
			"status":           string(status),
			"attempt_count":    attempts,
			"next_attempt_at":  nextAttemptAt,
			"last_error_class": errClassStr,
			"claim_token":      nil,
		}
		if status == delivery.StatusQuarantined {
			for k, v := range clearedContent {
				updates[k] = v
			}
		}
		return tx.Model(&deliveryRow{}).Where("id = ?", row.ID).Updates(updates).Error
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
		for i := range due {
			claimToken := newClaimToken()
			if err := tx.Model(&deliveryRow{}).Where("id = ?", due[i].ID).Updates(map[string]any{
				"status":      string(delivery.StatusInProgress),
				"claim_token": claimToken,
			}).Error; err != nil {
				return err
			}
			due[i].Status = string(delivery.StatusInProgress)
			due[i].ClaimToken = &claimToken
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

func (r *DeliveryRepository) FinalizeCancelled(ctx context.Context, id int64, claimToken string) error {
	updates := map[string]any{"status": string(delivery.StatusCancelled), "claim_token": nil}
	for k, v := range clearedContent {
		updates[k] = v
	}
	result := r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("id = ? AND status = ? AND claim_token = ?", id, string(delivery.StatusInProgress), claimToken).
		Updates(updates)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		slog.Warn("fcm delivery: finalize cancelled skipped — claim no longer matches (reclaimed or already finalized)", "id", id)
	}
	return nil
}

func (r *DeliveryRepository) ReclaimStale(ctx context.Context, staleThreshold time.Duration, now time.Time) (int64, error) {
	cutoff := now.Add(-staleThreshold)
	result := r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("status = ? AND updated_at < ?", string(delivery.StatusInProgress), cutoff).
		Updates(map[string]any{
			"status":          string(delivery.StatusPendingRetry),
			"next_attempt_at": now,
			// Clearing claim_token means a Finalize* call for the original (now
			// stale) claim no longer matches and safely no-ops instead of resurrecting
			// or overwriting whatever the row does next.
			"claim_token": nil,
		})
	return result.RowsAffected, result.Error
}

func (r *DeliveryRepository) PurgeTerminal(ctx context.Context, olderThan time.Time) (int64, error) {
	terminal := []string{
		string(delivery.StatusSuccess),
		string(delivery.StatusInvalid),
		string(delivery.StatusQuarantined),
		string(delivery.StatusCancelled),
	}
	result := r.db.WithContext(ctx).
		Where("status IN ? AND updated_at < ?", terminal, olderThan).
		Limit(purgeBatchSize).
		Delete(&deliveryRow{})
	return result.RowsAffected, result.Error
}

func (r *DeliveryRepository) CountPending(ctx context.Context) (int64, error) {
	var count int64
	err := r.db.WithContext(ctx).Model(&deliveryRow{}).
		Where("status IN ?", []string{string(delivery.StatusPendingRetry), string(delivery.StatusInProgress)}).
		Count(&count).Error
	return count, err
}

// warnIfClaimStale logs when an UPDATE's WHERE clause (including its claim_token
// match) touched no row, meaning the claim was reclaimed or already finalized by a
// different attempt.
func warnIfClaimStale(rowsAffected int64, outcome, eventID string, deviceTokenID int64) {
	if rowsAffected == 0 {
		slog.Warn("fcm delivery: finalize "+outcome+" skipped — claim no longer matches (reclaimed or already finalized)",
			"eventId", eventID, "deviceTokenId", deviceTokenID)
	}
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
	var claimToken string
	if row.ClaimToken != nil {
		claimToken = *row.ClaimToken
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
		ClaimToken:     claimToken,
		CreatedAt:      row.CreatedAt,
		UpdatedAt:      row.UpdatedAt,
	}, nil
}
