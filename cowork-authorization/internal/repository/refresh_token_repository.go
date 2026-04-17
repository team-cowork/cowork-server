package repository

import (
	"time"

	"github.com/cowork/authorization/internal/domain"
	"gorm.io/gorm"
)

type RefreshTokenRepository struct {
	db *gorm.DB
}

func NewRefreshTokenRepository(db *gorm.DB) *RefreshTokenRepository {
	return &RefreshTokenRepository{db: db}
}

func (r *RefreshTokenRepository) Create(token *domain.RefreshToken) error {
	return r.db.Create(token).Error
}

func (r *RefreshTokenRepository) FindByHash(hash string) (*domain.RefreshToken, error) {
	var token domain.RefreshToken
	if err := r.db.Where("token_hash = ?", hash).First(&token).Error; err != nil {
		return nil, err
	}
	return &token, nil
}

func (r *RefreshTokenRepository) DeleteByHash(hash string) error {
	return r.db.Where("token_hash = ?", hash).Delete(&domain.RefreshToken{}).Error
}

func (r *RefreshTokenRepository) DeleteExpired() error {
	return r.db.Where("expires_at < ?", time.Now()).Delete(&domain.RefreshToken{}).Error
}
