package repository

import (
	"context"
	"time"

	"gorm.io/gorm"

	"github.com/cowork/authorization/internal/domain"
)

type AgentTokenRepository struct {
	db *gorm.DB
}

func NewAgentTokenRepository(db *gorm.DB) *AgentTokenRepository {
	return &AgentTokenRepository{db: db}
}

func (r *AgentTokenRepository) Create(ctx context.Context, token *domain.AgentToken) error {
	return r.db.WithContext(ctx).Create(token).Error
}

func (r *AgentTokenRepository) ListByUser(ctx context.Context, userID int64) ([]domain.AgentToken, error) {
	var tokens []domain.AgentToken
	err := r.db.WithContext(ctx).Where("user_id = ?", userID).Order("id DESC").Find(&tokens).Error
	return tokens, err
}

// CountActiveByUser counts the user's tokens that are unrevoked and unexpired at the given time.
func (r *AgentTokenRepository) CountActiveByUser(ctx context.Context, userID int64, at time.Time) (int64, error) {
	var count int64
	err := r.db.WithContext(ctx).Model(&domain.AgentToken{}).
		Where("user_id = ? AND revoked_at IS NULL AND expires_at > ?", userID, at).
		Count(&count).Error
	return count, err
}

// FindByHash returns gorm.ErrRecordNotFound when no token has the hash.
func (r *AgentTokenRepository) FindByHash(ctx context.Context, hash string) (*domain.AgentToken, error) {
	var token domain.AgentToken
	if err := r.db.WithContext(ctx).Where("token_hash = ?", hash).First(&token).Error; err != nil {
		return nil, err
	}
	return &token, nil
}

// Revoke reports false when the user has no unrevoked token with the ID.
func (r *AgentTokenRepository) Revoke(ctx context.Context, id, userID int64, at time.Time) (bool, error) {
	result := r.db.WithContext(ctx).Model(&domain.AgentToken{}).
		Where("id = ? AND user_id = ? AND revoked_at IS NULL", id, userID).
		Update("revoked_at", at)
	return result.RowsAffected == 1, result.Error
}

// MarkUsed records a use only while the token is still unrevoked and unexpired at the given time.
// It reports false when a concurrent revocation or the expiry came first.
// MySQL counts only changed rows, so a second use at the same microsecond also reports false.
func (r *AgentTokenRepository) MarkUsed(ctx context.Context, id int64, at time.Time) (bool, error) {
	result := r.db.WithContext(ctx).Model(&domain.AgentToken{}).
		Where("id = ? AND revoked_at IS NULL AND expires_at > ?", id, at).
		Update("last_used_at", at)
	return result.RowsAffected == 1, result.Error
}
