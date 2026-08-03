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

// ReplaceInTransaction은 기존 리프레시 토큰 삭제와 신규 토큰 저장을 하나의 트랜잭션으로 묶는다.
// 둘을 별개로 실행하면 삭제 후 저장이 실패했을 때 세션이 소실될 수 있다.
func (r *RefreshTokenRepository) ReplaceInTransaction(oldHash string, newToken *domain.RefreshToken) error {
	return r.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("token_hash = ?", oldHash).Delete(&domain.RefreshToken{}).Error; err != nil {
			return err
		}
		return tx.Create(newToken).Error
	})
}

func (r *RefreshTokenRepository) DeleteExpired() error {
	return r.db.Where("expires_at < ?", time.Now()).Delete(&domain.RefreshToken{}).Error
}
