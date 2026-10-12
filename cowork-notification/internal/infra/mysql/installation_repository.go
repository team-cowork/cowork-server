package mysql

import (
	"context"
	"errors"
	"time"

	"github.com/cowork/cowork-notification/internal/apperr"
	"github.com/cowork/cowork-notification/internal/domain/installation"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type InstallationRepository struct {
	db *gorm.DB
}

func NewInstallationRepository(db *gorm.DB) *InstallationRepository {
	return &InstallationRepository{db: db}
}

func (r *InstallationRepository) Register(ctx context.Context, requested *installation.Installation) (installation.RegistrationResult, error) {
	var registration installation.RegistrationResult
	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// The FID-only unique key serializes registrations for one physical app
		// installation. The no-op duplicate update acquires the row lock even when
		// this account is taking ownership from another account.
		if err := tx.Exec(
			`INSERT INTO tb_device_installation (account_id, fid, platform)
			 VALUES (?, ?, ?)
			 ON DUPLICATE KEY UPDATE id = id`,
			requested.AccountID, requested.FID, requested.Platform,
		).Error; err != nil {
			return err
		}

		var existing installation.Installation
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("fid = ?", requested.FID).
			Take(&existing).Error; err != nil {
			return err
		}

		if existing.AccountID == requested.AccountID {
			if err := tx.Model(&installation.Installation{}).
				Where("id = ?", existing.ID).
				Updates(map[string]any{
					"platform":   requested.Platform,
					"updated_at": time.Now(),
				}).Error; err != nil {
				return err
			}
			registration.InstallationID = existing.ID
			requested.ID = existing.ID
			return nil
		}

		// Recreate the row instead of updating account_id in place. Delivery retry
		// rows use installation_id as their ownership generation; a new ID makes
		// retries for the previous account fail CurrentFID and become CANCELLED.
		if err := tx.Delete(&installation.Installation{}, existing.ID).Error; err != nil {
			return err
		}
		requested.ID = 0
		requested.CreatedAt = time.Time{}
		requested.UpdatedAt = time.Time{}
		if err := tx.Create(requested).Error; err != nil {
			return err
		}
		registration = installation.RegistrationResult{
			InstallationID:    requested.ID,
			PreviousAccountID: existing.AccountID,
			Reassigned:        true,
		}
		return nil
	})
	return registration, err
}

func (r *InstallationRepository) FindByID(ctx context.Context, id int64) (*installation.Installation, error) {
	var i installation.Installation
	err := r.db.WithContext(ctx).Where("id = ?", id).Take(&i).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, apperr.NotFound("installation not found")
	}
	if err != nil {
		return nil, err
	}
	return &i, nil
}

// CurrentFID satisfies delivery.InstallationVerifier: it lets the FCM retry worker
// check whether an installation row still exists before resending to it.
func (r *InstallationRepository) CurrentFID(ctx context.Context, installationID int64) (string, int64, bool, error) {
	i, err := r.FindByID(ctx, installationID)
	if err != nil {
		var appErr *apperr.AppError
		if errors.As(err, &appErr) && appErr.Code == 404 {
			return "", 0, false, nil
		}
		return "", 0, false, err
	}
	return i.FID, i.AccountID, true, nil
}

// DeleteInvalidFID satisfies delivery.InvalidInstallationHandler.
func (r *InstallationRepository) DeleteInvalidFID(ctx context.Context, fid string) error {
	return r.DeleteByFIDs(ctx, []string{fid})
}

func (r *InstallationRepository) FindByAccountIDs(ctx context.Context, accountIDs []int64) (map[int64][]installation.Installation, error) {
	if len(accountIDs) == 0 {
		return nil, nil
	}
	var rows []installation.Installation
	if err := r.db.WithContext(ctx).Where("account_id IN ?", accountIDs).Find(&rows).Error; err != nil {
		return nil, err
	}
	result := make(map[int64][]installation.Installation, len(accountIDs))
	for _, i := range rows {
		result[i.AccountID] = append(result[i.AccountID], i)
	}
	return result, nil
}

func (r *InstallationRepository) DeleteByFIDs(ctx context.Context, fids []string) error {
	if len(fids) == 0 {
		return nil
	}
	return r.db.WithContext(ctx).Where("fid IN ?", fids).Delete(&installation.Installation{}).Error
}

func (r *InstallationRepository) DeleteByAccountIDAndFID(ctx context.Context, accountID int64, fid string) error {
	result := r.db.WithContext(ctx).Where("account_id = ? AND fid = ?", accountID, fid).Delete(&installation.Installation{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return apperr.NotFound("installation not found")
	}
	return nil
}
