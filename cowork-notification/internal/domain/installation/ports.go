package installation

import (
	"context"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/delivery"
	"github.com/cowork/cowork-notification/internal/infra/fcm"
)

type Repository interface {
	Register(ctx context.Context, i *Installation) (RegistrationResult, error)
	FindByID(ctx context.Context, id int64) (*Installation, error)
	FindByAccountIDs(ctx context.Context, accountIDs []int64) (map[int64][]Installation, error)
	DeleteByAccountIDAndFID(ctx context.Context, accountID int64, fid string) error
	DeleteByFIDs(ctx context.Context, fids []string) error // for FCM invalid FID bulk cleanup
}

type FCMSender interface {
	Send(ctx context.Context, messages []fcm.Message, title, body string) ([]fcm.Result, error)
}

type NotificationPreferenceResolver interface {
	FindChannelNotificationPreferences(
		ctx context.Context,
		accountIDs []int64,
		channelID int64,
	) (map[int64]ChannelNotificationPreference, error)
}

type InstallationService interface {
	RegisterInstallation(ctx context.Context, accountID int64, fid, platform string) error
	DeleteInstallation(ctx context.Context, accountID int64, fid string) error
	Notify(ctx context.Context, eventID string, targetUserIDs []int64, forcedUserIDs []int64, title, body string, channelID int64) ([]int64, error)
}

// DeliveryRepository is the subset of delivery.Repository the installation service
// needs to durably track per-installation FCM outcomes across retries. Declared
// locally so this package depends only on the method set it actually uses.
type DeliveryRepository interface {
	ResumeOrCreate(
		ctx context.Context,
		eventID string,
		targets []delivery.Target,
		title, body string,
		data map[string]string,
	) ([]delivery.Target, error)
	FinalizeSuccess(ctx context.Context, eventID string, installationID int64, claimToken string) error
	FinalizeInvalid(ctx context.Context, eventID string, installationID int64, claimToken string) error
	FinalizeFailure(ctx context.Context, eventID string, installationID int64, claimToken string, errClass delivery.ErrorClass, now time.Time) (delivery.Status, error)
	FinalizeCancelled(ctx context.Context, eventID string, installationID int64, claimToken string) error
}
