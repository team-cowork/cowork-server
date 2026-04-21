package token

import (
	"context"
	"log/slog"
)

type Service struct {
	repo Repository
	fcm  FCMSender
	pref PreferenceClient
}

func NewService(repo Repository, fcm FCMSender, pref PreferenceClient) *Service {
	return &Service{repo: repo, fcm: fcm, pref: pref}
}

func (s *Service) RegisterToken(ctx context.Context, accountID int64, tkn, platform string) error {
	return s.repo.Save(ctx, &DeviceToken{
		AccountID: accountID,
		Token:     tkn,
		Platform:  Platform(platform),
	})
}

func (s *Service) DeleteToken(ctx context.Context, accountID int64, tkn string) error {
	return s.repo.DeleteByAccountIDAndToken(ctx, accountID, tkn)
}

func (s *Service) Notify(ctx context.Context, targetUserIDs []int64, title, body string, channelID int64) error {
	var allTokens []string
	for _, uid := range targetUserIDs {
		if channelID > 0 {
			enabled, err := s.pref.IsNotificationEnabled(ctx, uid, channelID)
			if err != nil {
				slog.Warn("preference check failed, defaulting to enabled", "accountId", uid, "err", err)
				enabled = true
			}
			if !enabled {
				continue
			}
		}
		tokens, err := s.repo.FindByAccountID(ctx, uid)
		if err != nil {
			slog.Warn("failed to fetch tokens", "accountId", uid, "err", err)
			continue
		}
		for _, t := range tokens {
			allTokens = append(allTokens, t.Token)
		}
	}

	if len(allTokens) == 0 {
		return nil
	}

	invalidTokens, err := s.fcm.Send(ctx, allTokens, title, body, nil)
	if err != nil {
		return err
	}
	for _, t := range invalidTokens {
		if delErr := s.repo.DeleteByToken(ctx, t); delErr != nil {
			slog.Warn("failed to delete invalid token", "token", t, "err", delErr)
		}
	}
	return nil
}
