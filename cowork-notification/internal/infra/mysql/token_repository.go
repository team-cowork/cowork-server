package mysql

import (
	"context"
	"errors"
	"time"

	"github.com/cowork/cowork-notification/internal/apperr"
	"github.com/cowork/cowork-notification/internal/domain/token"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type TokenRepository struct {
	db *gorm.DB
}

func NewTokenRepository(db *gorm.DB) *TokenRepository {
	return &TokenRepository{db: db}
}

func (r *TokenRepository) Register(ctx context.Context, requested *token.DeviceToken) (token.RegistrationResult, error) {
	var registration token.RegistrationResult
	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// The token-only unique key serializes registrations for one physical app
		// installation. The no-op duplicate update acquires the row lock even when
		// this account is taking ownership from another account.
		if err := tx.Exec(
			`INSERT INTO tb_device_token (account_id, token, platform)
			 VALUES (?, ?, ?)
			 ON DUPLICATE KEY UPDATE id = id`,
			requested.AccountID, requested.Token, requested.Platform,
		).Error; err != nil {
			return err
		}

		var existing token.DeviceToken
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("token = ?", requested.Token).
			Take(&existing).Error; err != nil {
			return err
		}

		if existing.AccountID == requested.AccountID {
			if err := tx.Model(&token.DeviceToken{}).
				Where("id = ?", existing.ID).
				Updates(map[string]any{
					"platform":   requested.Platform,
					"updated_at": time.Now(),
				}).Error; err != nil {
				return err
			}
			registration.DeviceTokenID = existing.ID
			requested.ID = existing.ID
			return nil
		}

		// Recreate the row instead of updating account_id in place. Delivery retry
		// rows use device_token_id as their ownership generation; a new ID makes
		// retries for the previous account fail CurrentToken and become CANCELLED.
		if err := tx.Delete(&token.DeviceToken{}, existing.ID).Error; err != nil {
			return err
		}
		requested.ID = 0
		requested.CreatedAt = time.Time{}
		requested.UpdatedAt = time.Time{}
		if err := tx.Create(requested).Error; err != nil {
			return err
		}
		registration = token.RegistrationResult{
			DeviceTokenID:     requested.ID,
			PreviousAccountID: existing.AccountID,
			Reassigned:        true,
		}
		return nil
	})
	return registration, err
}

func (r *TokenRepository) FindByID(ctx context.Context, id int64) (*token.DeviceToken, error) {
	var t token.DeviceToken
	err := r.db.WithContext(ctx).Where("id = ?", id).Take(&t).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, apperr.NotFound("token not found")
	}
	if err != nil {
		return nil, err
	}
	return &t, nil
}

// CurrentToken satisfies delivery.TokenVerifier: it lets the FCM retry worker check
// whether a device token row still exists before resending to it.
func (r *TokenRepository) CurrentToken(ctx context.Context, deviceTokenID int64) (string, int64, bool, error) {
	t, err := r.FindByID(ctx, deviceTokenID)
	if err != nil {
		var appErr *apperr.AppError
		if errors.As(err, &appErr) && appErr.Code == 404 {
			return "", 0, false, nil
		}
		return "", 0, false, err
	}
	return t.Token, t.AccountID, true, nil
}

// DeleteInvalidToken satisfies delivery.InvalidTokenHandler.
func (r *TokenRepository) DeleteInvalidToken(ctx context.Context, tkn string) error {
	return r.DeleteByTokens(ctx, []string{tkn})
}

func (r *TokenRepository) FindByAccountID(ctx context.Context, accountID int64) ([]token.DeviceToken, error) {
	var tokens []token.DeviceToken
	err := r.db.WithContext(ctx).Where("account_id = ?", accountID).Find(&tokens).Error
	return tokens, err
}

func (r *TokenRepository) FindByAccountIDs(ctx context.Context, accountIDs []int64) (map[int64][]token.DeviceToken, error) {
	if len(accountIDs) == 0 {
		return nil, nil
	}
	var rows []token.DeviceToken
	if err := r.db.WithContext(ctx).Where("account_id IN ?", accountIDs).Find(&rows).Error; err != nil {
		return nil, err
	}
	result := make(map[int64][]token.DeviceToken, len(accountIDs))
	for _, t := range rows {
		result[t.AccountID] = append(result[t.AccountID], t)
	}
	return result, nil
}

func (r *TokenRepository) DeleteByTokens(ctx context.Context, tokens []string) error {
	if len(tokens) == 0 {
		return nil
	}
	return r.db.WithContext(ctx).Where("token IN ?", tokens).Delete(&token.DeviceToken{}).Error
}

func (r *TokenRepository) DeleteByAccountIDAndToken(ctx context.Context, accountID int64, tkn string) error {
	result := r.db.WithContext(ctx).Where("account_id = ? AND token = ?", accountID, tkn).Delete(&token.DeviceToken{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return apperr.NotFound("token not found")
	}
	return nil
}
