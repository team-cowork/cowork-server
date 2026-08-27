package repository

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/cowork/authorization/internal/domain"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

var (
	ErrIdentityCommandConflict = errors.New("identity command idempotency conflict")
	ErrIdentityResultConflict  = fmt.Errorf("%w: conflicting result", domain.ErrIdentityResultRejected)
	ErrIdentityResultUnknown   = fmt.Errorf("%w: unknown operation", domain.ErrIdentityResultRejected)
	ErrIdentityResultUserID    = fmt.Errorf("%w: userId mismatch", domain.ErrIdentityResultRejected)
)

type UserIdentityOperationRepository struct {
	db *gorm.DB
}

func NewUserIdentityOperationRepository(db *gorm.DB) *UserIdentityOperationRepository {
	return &UserIdentityOperationRepository{db: db}
}

// Submit atomically stores the operation and its Kafka command outbox row. An
// exact idempotency retry observes the original operation; conflicting reuse is
// rejected before any new command can be emitted.
func (r *UserIdentityOperationRepository) Submit(
	ctx context.Context,
	command domain.UserIdentityCommand,
	topic string,
) (string, error) {
	if err := domain.ValidateUserIdentityCommand(command); err != nil {
		return "", err
	}
	payload, err := domain.MarshalCanonical(command)
	if err != nil {
		return "", err
	}
	requestHash := payloadHash(payload)
	operationID := command.OperationID

	err = r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var existing domain.UserIdentityOperation
		err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("idempotency_key = ?", command.IdempotencyKey).
			First(&existing).Error
		switch {
		case err == nil:
			if existing.UserID != command.UserID || existing.RequestHash != requestHash {
				return ErrIdentityCommandConflict
			}
			operationID = existing.OperationID
			return nil
		case !errors.Is(err, gorm.ErrRecordNotFound):
			return err
		}

		operation := domain.UserIdentityOperation{
			OperationID:    command.OperationID,
			IdempotencyKey: command.IdempotencyKey,
			UserID:         command.UserID,
			RequestHash:    requestHash,
			Status:         domain.UserIdentityPending,
		}
		if err := tx.Create(&operation).Error; err != nil {
			return err
		}
		return tx.Exec(
			`INSERT INTO tb_kafka_outbox (topic, event_key, payload) VALUES (?, ?, ?)`,
			topic,
			fmt.Sprintf("%d", command.UserID),
			payload,
		).Error
	})
	return operationID, err
}

func (r *UserIdentityOperationRepository) Find(
	ctx context.Context,
	operationID string,
) (*domain.UserIdentityOperation, error) {
	var operation domain.UserIdentityOperation
	if err := r.db.WithContext(ctx).
		Where("operation_id = ?", operationID).
		First(&operation).Error; err != nil {
		return nil, err
	}
	return &operation, nil
}

// ApplyResult is the sole result transition. The first strict result completes
// a pending operation; only an exact canonical duplicate is accepted later.
func (r *UserIdentityOperationRepository) ApplyResult(
	ctx context.Context,
	key string,
	result domain.UserIdentityCommandResult,
) error {
	if key != result.OperationID {
		return fmt.Errorf("result key does not match operationId")
	}
	if err := domain.ValidateUserIdentityCommandResult(result); err != nil {
		return err
	}
	payload, err := domain.MarshalCanonical(result)
	if err != nil {
		return err
	}
	resultHash := payloadHash(payload)

	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var operation domain.UserIdentityOperation
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("operation_id = ?", result.OperationID).
			First(&operation).Error; err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				return ErrIdentityResultUnknown
			}
			return err
		}
		if operation.ResultHash != nil {
			if *operation.ResultHash == resultHash {
				return nil
			}
			return ErrIdentityResultConflict
		}
		if operation.Status != domain.UserIdentityPending {
			return ErrIdentityResultConflict
		}

		completedAt := result.OccurredAt.UTC().Truncate(time.Microsecond)
		updates := map[string]any{
			"status":       result.Status,
			"result_hash":  resultHash,
			"completed_at": completedAt,
		}
		if result.Status == domain.UserIdentitySucceeded {
			if result.UserID == nil || *result.UserID != operation.UserID {
				return ErrIdentityResultUserID
			}
			updates["result_user_id"] = *result.UserID
			updates["error_code"] = nil
			updates["error_message"] = nil
		} else {
			updates["result_user_id"] = nil
			updates["error_code"] = result.Error.Code
			updates["error_message"] = result.Error.Message
		}

		updated := tx.Model(&domain.UserIdentityOperation{}).
			Where("operation_id = ? AND status = ? AND result_hash IS NULL", operation.OperationID, domain.UserIdentityPending).
			Updates(updates)
		if updated.Error != nil {
			return updated.Error
		}
		if updated.RowsAffected != 1 {
			return ErrIdentityResultConflict
		}
		return nil
	})
}

func payloadHash(payload []byte) string {
	digest := sha256.Sum256(payload)
	return hex.EncodeToString(digest[:])
}
