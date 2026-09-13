package token

import (
	"context"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/delivery"
	"github.com/cowork/cowork-notification/internal/infra/fcm"
)

type Repository interface {
	Save(ctx context.Context, t *DeviceToken) error
	FindByID(ctx context.Context, id int64) (*DeviceToken, error)
	FindByAccountID(ctx context.Context, accountID int64) ([]DeviceToken, error)
	FindByAccountIDs(ctx context.Context, accountIDs []int64) (map[int64][]DeviceToken, error)
	DeleteByAccountIDAndToken(ctx context.Context, accountID int64, token string) error
	DeleteByTokens(ctx context.Context, tokens []string) error // for FCM invalid token bulk cleanup
}

type FCMSender interface {
	Send(ctx context.Context, tokens []string, title, body string, data map[string]string) ([]fcm.TokenResult, error)
}

type NotificationPreferenceResolver interface {
	AreNotificationsEnabled(ctx context.Context, accountIDs []int64, channelID int64) (map[int64]bool, error)
}

type TokenService interface {
	RegisterToken(ctx context.Context, accountID int64, token, platform string) error
	DeleteToken(ctx context.Context, accountID int64, token string) error
	Notify(ctx context.Context, eventID string, targetUserIDs []int64, forcedUserIDs []int64, title, body string, channelID int64) ([]int64, error)
}

// DeliveryRepository is the subset of delivery.Repository the token service needs to
// durably track per-token FCM outcomes across retries. Declared locally so this
// package depends only on the method set it actually uses.
type DeliveryRepository interface {
	ResumeOrCreate(
		ctx context.Context,
		eventID string,
		targets []delivery.TargetToken,
		title, body string,
		data map[string]string,
	) ([]delivery.TargetToken, error)
	FinalizeSuccess(ctx context.Context, eventID string, deviceTokenID int64) error
	FinalizeInvalid(ctx context.Context, eventID string, deviceTokenID int64) error
	FinalizeFailure(ctx context.Context, eventID string, deviceTokenID int64, errClass delivery.ErrorClass, now time.Time) (delivery.Status, error)
}
