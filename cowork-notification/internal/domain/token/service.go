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

func (s *Service) Notify(ctx context.Context, targetUserIDs []int64, forcedUserIDs []int64, title, body string, channelID int64) error {
	forcedSet := make(map[int64]bool, len(forcedUserIDs))
	for _, id := range forcedUserIDs {
		forcedSet[id] = true
	}

	// forcedUserIDs는 뮤트 무시하고 무조건 포함
	enabledIDs := make([]int64, 0, len(targetUserIDs))
	enabledIDs = append(enabledIDs, forcedUserIDs...)

	// 나머지는 preference 확인
	if channelID > 0 {
		nonForcedIDs := make([]int64, 0, len(targetUserIDs))
		for _, uid := range targetUserIDs {
			if !forcedSet[uid] {
				nonForcedIDs = append(nonForcedIDs, uid)
			}
		}
		if len(nonForcedIDs) > 0 {
			enabledMap, err := s.pref.AreNotificationsEnabled(ctx, nonForcedIDs, channelID)
			if err != nil {
				slog.Warn("batch preference check failed, defaulting to enabled", "err", err)
				enabledIDs = append(enabledIDs, nonForcedIDs...)
			} else {
				for _, uid := range nonForcedIDs {
					if enabled, ok := enabledMap[uid]; !ok || enabled {
						enabledIDs = append(enabledIDs, uid)
					}
				}
			}
		}
	} else {
		for _, uid := range targetUserIDs {
			if !forcedSet[uid] {
				enabledIDs = append(enabledIDs, uid)
			}
		}
	}

	if len(enabledIDs) == 0 {
		return nil
	}

	tokenMap, err := s.repo.FindByAccountIDs(ctx, enabledIDs)
	if err != nil {
		return err
	}
	var allTokens []string
	for _, tokens := range tokenMap {
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
	if len(invalidTokens) > 0 {
		if delErr := s.repo.DeleteByTokens(ctx, invalidTokens); delErr != nil {
			slog.Warn("failed to bulk delete invalid tokens", "count", len(invalidTokens), "err", delErr)
		}
	}
	return nil
}
