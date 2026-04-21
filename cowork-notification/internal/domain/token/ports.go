package token

import "context"

type Repository interface {
	Save(ctx context.Context, t *DeviceToken) error
	FindByAccountID(ctx context.Context, accountID int64) ([]DeviceToken, error)
	DeleteByAccountIDAndToken(ctx context.Context, accountID int64, token string) error
	DeleteByToken(ctx context.Context, token string) error // for FCM invalid token cleanup
}

type FCMSender interface {
	Send(ctx context.Context, tokens []string, title, body string, data map[string]string) (invalidTokens []string, err error)
}

type PreferenceClient interface {
	IsNotificationEnabled(ctx context.Context, accountID, channelID int64) (bool, error)
}

type TokenService interface {
	RegisterToken(ctx context.Context, accountID int64, token, platform string) error
	DeleteToken(ctx context.Context, accountID int64, token string) error
	Notify(ctx context.Context, targetUserIDs []int64, title, body string, channelID int64) error
}
