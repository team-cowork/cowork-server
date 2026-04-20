package mysql

import (
	"context"

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

func (r *TokenRepository) DeleteByToken(ctx context.Context, tkn string) error {
	return r.db.WithContext(ctx).Where("token = ?", tkn).Delete(&token.DeviceToken{}).Error
}
