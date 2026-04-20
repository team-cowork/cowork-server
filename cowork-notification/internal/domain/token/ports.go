package token

import "context"

type Repository interface {
	Save(ctx context.Context, t *DeviceToken) error
	FindByAccountID(ctx context.Context, accountID int64) ([]DeviceToken, error)
	DeleteByToken(ctx context.Context, token string) error
}

type FCMSender interface {
	Send(ctx context.Context, tokens []string, title, body string, data map[string]string) (invalidTokens []string, err error)
}

type PreferenceClient interface {
	IsNotificationEnabled(ctx context.Context, accountID, channelID int64) (bool, error)
}
