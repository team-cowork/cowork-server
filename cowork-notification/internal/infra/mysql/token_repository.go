package mysql

import (
	"context"

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

func (r *TokenRepository) Save(ctx context.Context, t *token.DeviceToken) error {
	return r.db.WithContext(ctx).
		Clauses(clause.OnConflict{
			Columns:   []clause.Column{{Name: "account_id"}, {Name: "token"}},
			DoUpdates: clause.AssignmentColumns([]string{"platform", "updated_at"}),
		}).
		Create(t).Error
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
