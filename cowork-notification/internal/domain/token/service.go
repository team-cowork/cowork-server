package token

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/delivery"
	"github.com/cowork/cowork-notification/internal/infra/fcm"
	"github.com/cowork/cowork-notification/internal/monitoring"
)

type Service struct {
	repo     Repository
	fcm      FCMSender
	pref     NotificationPreferenceResolver
	delivery DeliveryRepository
}

func NewService(repo Repository, fcmSender FCMSender, pref NotificationPreferenceResolver, deliveryRepo DeliveryRepository) *Service {
	return &Service{repo: repo, fcm: fcmSender, pref: pref, delivery: deliveryRepo}
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

func (s *Service) Notify(
	ctx context.Context,
	eventID string,
	targetUserIDs []int64,
	forcedUserIDs []int64,
	title, body string,
	channelID int64,
) ([]int64, error) {
	forcedSet := make(map[int64]bool, len(forcedUserIDs))
	enabledIDs := make([]int64, 0, len(targetUserIDs)+len(forcedUserIDs))
	for _, id := range forcedUserIDs {
		if forcedSet[id] {
			continue
		}
		forcedSet[id] = true
		enabledIDs = append(enabledIDs, id)
	}

	// forcedUserIDs는 뮤트 무시하고 무조건 포함
	nonForcedIDs := make([]int64, 0, len(targetUserIDs))
	nonForcedSet := make(map[int64]bool, len(targetUserIDs))
	for _, uid := range targetUserIDs {
		if !forcedSet[uid] && !nonForcedSet[uid] {
			nonForcedSet[uid] = true
			nonForcedIDs = append(nonForcedIDs, uid)
		}
	}

	// 나머지는 preference 확인
	if channelID > 0 {
		if len(nonForcedIDs) > 0 {
			enabledMap, err := s.pref.AreNotificationsEnabled(ctx, nonForcedIDs, channelID)
			if err != nil {
				// Fail closed. The Kafka trigger remains uncommitted and is retried;
				// defaulting to enabled could notify an explicitly opted-out user.
				return nil, fmt.Errorf("resolve channel notification preferences: %w", err)
			}
			for _, uid := range nonForcedIDs {
				if enabled, ok := enabledMap[uid]; !ok || enabled {
					enabledIDs = append(enabledIDs, uid)
				}
			}
		}
	} else {
		enabledIDs = append(enabledIDs, nonForcedIDs...)
	}

	if len(enabledIDs) == 0 {
		return enabledIDs, nil
	}

	tokenMap, err := s.repo.FindByAccountIDs(ctx, enabledIDs)
	if err != nil {
		return nil, err
	}
	var allTargets []delivery.TargetToken
	for _, tokens := range tokenMap {
		for _, t := range tokens {
			allTargets = append(allTargets, delivery.TargetToken{DeviceTokenID: t.ID, Token: t.Token})
		}
	}
	if len(allTargets) == 0 {
		return enabledIDs, nil
	}

	if err := ctx.Err(); err != nil {
		return nil, err
	}

	// eventId is the (eventId, deviceTokenId) canonical delivery key described in
	// docs/todo/items/33-reliability/fcm-partial-failure-retry.md. A producer that has
	// not yet been updated to send one falls back to a single non-durable attempt
	// (today's behavior) instead of failing the notification outright.
	toSend := allTargets
	durable := eventID != ""
	if durable {
		toSend, err = s.delivery.ResumeOrCreate(ctx, eventID, allTargets, title, body, nil)
		if err != nil {
			return nil, fmt.Errorf("resume or create delivery ledger: %w", err)
		}
		if len(toSend) == 0 {
			return enabledIDs, nil
		}
	} else {
		slog.Warn("notification event missing eventId; falling back to non-durable single-attempt delivery")
	}

	tokensToSend := make([]string, len(toSend))
	targetByToken := make(map[string]delivery.TargetToken, len(toSend))
	for i, t := range toSend {
		tokensToSend[i] = t.Token
		targetByToken[t.Token] = t
	}

	results, err := s.fcm.Send(ctx, tokensToSend, title, body, nil)
	if err != nil {
		return nil, err
	}

	var invalidTokens []string
	for _, r := range results {
		target := targetByToken[r.Token]
		monitoring.RecordFCMDeliveryOutcome(string(r.Outcome), "initial")
		switch r.Outcome {
		case fcm.OutcomeSuccess:
			if durable {
				if err := s.delivery.FinalizeSuccess(ctx, eventID, target.DeviceTokenID); err != nil {
					slog.Warn("failed to finalize successful delivery", "err", err)
				}
			}
		case fcm.OutcomeInvalid:
			invalidTokens = append(invalidTokens, r.Token)
			if durable {
				if err := s.delivery.FinalizeInvalid(ctx, eventID, target.DeviceTokenID); err != nil {
					slog.Warn("failed to finalize invalid delivery", "err", err)
				}
			}
		case fcm.OutcomeUnclassified:
			if durable {
				s.finalizeFailure(ctx, eventID, target.DeviceTokenID, delivery.ErrorClassUnclassified)
			}
		default: // fcm.OutcomeRetryable
			if durable {
				s.finalizeFailure(ctx, eventID, target.DeviceTokenID, delivery.ErrorClassRetryable)
			}
		}
	}
	if len(invalidTokens) > 0 {
		if delErr := s.repo.DeleteByTokens(ctx, invalidTokens); delErr != nil {
			slog.Warn("failed to bulk delete invalid tokens", "count", len(invalidTokens), "err", delErr)
		}
	}
	return enabledIDs, nil
}

func (s *Service) finalizeFailure(ctx context.Context, eventID string, deviceTokenID int64, errClass delivery.ErrorClass) {
	status, err := s.delivery.FinalizeFailure(ctx, eventID, deviceTokenID, errClass, time.Now())
	if err != nil {
		slog.Warn("failed to finalize delivery failure", "errClass", errClass, "err", err)
		return
	}
	if status == delivery.StatusQuarantined {
		monitoring.RecordFCMDeliveryQuarantined(string(errClass))
	}
}
