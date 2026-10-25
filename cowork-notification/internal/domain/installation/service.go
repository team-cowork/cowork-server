package installation

import (
	"context"
	"fmt"
	"log/slog"
	"regexp"
	"time"

	"github.com/cowork/cowork-notification/internal/apperr"
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

// fidPattern matches a Firebase installation ID: URL-safe base64 whose first 4 bits are
// 0b0111. Freshly generated FIDs are 22 characters on every Firebase Installations SDK
// (iOS, Android and Web). The Android SDK instead keeps a legacy Instance ID as the
// installation's FID when it migrates one; that ID is 8 bytes with the same prefix, so
// 11 characters. It rejects FCM registration tokens, which are longer and contain ':'.
var fidPattern = regexp.MustCompile(`^[cdef](?:[A-Za-z0-9_-]{10}|[A-Za-z0-9_-]{21})$`)

func (s *Service) RegisterInstallation(ctx context.Context, accountID int64, fid, platform string) error {
	if err := validateFID(fid); err != nil {
		return err
	}
	parsedPlatform, err := parsePlatform(platform)
	if err != nil {
		return err
	}

	result, err := s.repo.Register(ctx, &Installation{
		AccountID: accountID,
		FID:       fid,
		Platform:  parsedPlatform,
	})
	if err != nil {
		return err
	}
	if result.Reassigned {
		slog.Info(
			"Reassigned installation ownership",
			"installationId", result.InstallationID,
			"previousAccountId", result.PreviousAccountID,
			"accountId", accountID,
		)
	}
	return nil
}

func (s *Service) DeleteInstallation(ctx context.Context, accountID int64, fid string) error {
	if err := validateFID(fid); err != nil {
		return err
	}
	if err := s.repo.DeleteByAccountIDAndFID(ctx, accountID, fid); err != nil {
		return err
	}
	// Never log the raw FID. The authenticated account is sufficient to correlate an
	// explicit logout/account-switch revocation.
	slog.Info("Revoked installation", "accountId", accountID)
	return nil
}

func validateFID(fid string) error {
	if !fidPattern.MatchString(fid) {
		return apperr.BadRequest("fid must be a Firebase installation ID")
	}
	return nil
}

func parsePlatform(value string) (Platform, error) {
	switch Platform(value) {
	case PlatformAndroid, PlatformIOS, PlatformWeb:
		return Platform(value), nil
	default:
		return "", apperr.BadRequest("platform must be ANDROID, IOS, or WEB")
	}
}

func (s *Service) Notify(
	ctx context.Context,
	eventID string,
	targetUserIDs []int64,
	forcedUserIDs []int64,
	title, body string,
	channelID, teamID int64,
) ([]int64, error) {
	forcedIDs := make([]int64, 0, len(forcedUserIDs))
	forcedSet := make(map[int64]bool, len(forcedUserIDs))
	for _, id := range forcedUserIDs {
		if forcedSet[id] {
			continue
		}
		forcedSet[id] = true
		forcedIDs = append(forcedIDs, id)
	}

	nonForcedIDs := make([]int64, 0, len(targetUserIDs))
	nonForcedSet := make(map[int64]bool, len(targetUserIDs))
	for _, uid := range targetUserIDs {
		if !forcedSet[uid] && !nonForcedSet[uid] {
			nonForcedSet[uid] = true
			nonForcedIDs = append(nonForcedIDs, uid)
		}
	}

	recipients := append(append(make([]int64, 0, len(forcedIDs)+len(nonForcedIDs)), forcedIDs...), nonForcedIDs...)
	if len(recipients) == 0 {
		return recipients, nil
	}
	preferences, err := s.pref.FindRecipientPreferences(ctx, recipients, channelID, teamID)
	if err != nil {
		// Fail closed. The Kafka trigger remains uncommitted and is retried;
		// defaulting to enabled could notify an explicitly opted-out user.
		return nil, fmt.Errorf("resolve recipient notification preferences: %w", err)
	}

	// 방해 금지는 모든 수신자에게 적용하고, 알림 수준과 음소거는 멘션, 답글 대상(forcedUserIDs)이 아닌 채널 메시지에만 적용한다.
	now := time.Now()
	enabledIDs := make([]int64, 0, len(recipients))
	for _, uid := range forcedIDs {
		if !preferences.DoNotDisturb(uid, now) {
			enabledIDs = append(enabledIDs, uid)
		}
	}
	for _, uid := range nonForcedIDs {
		if preferences.DoNotDisturb(uid, now) {
			continue
		}
		if channelID > 0 && !preferences.ReceivesAllChannelMessages(uid, teamID > 0, now) {
			continue
		}
		enabledIDs = append(enabledIDs, uid)
	}

	if len(enabledIDs) == 0 {
		return enabledIDs, nil
	}

	installationMap, err := s.repo.FindByAccountIDs(ctx, enabledIDs)
	if err != nil {
		return nil, err
	}
	var allTargets []delivery.Target
	for _, installations := range installationMap {
		for _, i := range installations {
			allTargets = append(allTargets, delivery.Target{InstallationID: i.ID, FID: i.FID})
		}
	}
	if len(allTargets) == 0 {
		return enabledIDs, nil
	}

	if err := ctx.Err(); err != nil {
		return nil, err
	}

	// eventId is the (eventId, installationId) canonical delivery key. A producer that
	// has not yet been updated to send one falls back to a single non-durable attempt
	// (today's behavior) instead of failing the notification outright.
	toSend := allTargets
	durable := eventID != ""
	if durable {
		toSend, err = s.delivery.ResumeOrCreate(ctx, eventID, allTargets, title, body, eventData(eventID))
		if err != nil {
			return nil, fmt.Errorf("resume or create delivery ledger: %w", err)
		}
		if len(toSend) == 0 {
			return enabledIDs, nil
		}
	} else {
		slog.Warn("notification event missing eventId; falling back to non-durable single-attempt delivery")
	}

	toSend, messages, err := s.excludeStaleTargets(ctx, eventID, enabledIDs, toSend)
	if err != nil {
		return nil, err
	}
	if len(toSend) == 0 {
		return enabledIDs, nil
	}

	// fcm.Send appends one Result per attempted FID in input order (see its doc
	// comment), so results[i] always corresponds to toSend[i] — even when it returns
	// early with a partial result set. Finalization must retain the claimed
	// InstallationID instead of looking the FID up again because an ownership transfer
	// replaces the row with a new ID while an older delivery is in flight.
	//
	// The Firebase Admin SDK's default HTTP client retries 503s and network errors up to
	// 4 times, honoring Retry-After for as long as 2 minutes, so an unbounded ctx here
	// would let this call alone approach the retry worker's staleInProgressAfter window
	// while these rows are still held IN_PROGRESS — another replica's reclaim could then
	// resend them. 30s keeps a single attempt well clear of that.
	sendCtx, sendCancel := context.WithTimeout(ctx, 30*time.Second)
	results, sendErr := s.fcm.Send(sendCtx, messages, title, body)
	sendCancel()
	if sendErr != nil {
		slog.Warn("fcm send returned early; finalizing whatever results were attempted",
			"attempted", len(results), "total", len(messages), "err", sendErr)
	}

	// Finalizing is bookkeeping for an FCM call that has already happened — it must
	// run even if ctx (the Kafka consumer's lease context) is cancelled or expires
	// while we do it, otherwise an FID FCM already accepted could be resent by the
	// retry worker. A short-lived detached context keeps these DB writes from being
	// aborted by a ctx that dies for reasons unrelated to whether the writes succeed.
	finalizeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
	defer cancel()

	var invalidFIDs []string
	for i, r := range results {
		target := toSend[i]
		monitoring.RecordFCMDeliveryOutcome(string(r.Outcome), "initial")
		switch r.Outcome {
		case fcm.OutcomeSuccess:
			if durable {
				if err := s.delivery.FinalizeSuccess(finalizeCtx, eventID, target.InstallationID, target.ClaimToken); err != nil {
					slog.Warn("failed to finalize successful delivery", "err", err)
				}
			}
		case fcm.OutcomeInvalid:
			invalidFIDs = append(invalidFIDs, r.FID)
			if durable {
				if err := s.delivery.FinalizeInvalid(finalizeCtx, eventID, target.InstallationID, target.ClaimToken); err != nil {
					slog.Warn("failed to finalize invalid delivery", "err", err)
				}
			}
		case fcm.OutcomeUnclassified:
			if durable {
				s.finalizeFailure(finalizeCtx, eventID, target, delivery.ErrorClassUnclassified)
			}
		default: // fcm.OutcomeRetryable
			if durable {
				s.finalizeFailure(finalizeCtx, eventID, target, delivery.ErrorClassRetryable)
			}
		}
	}
	if len(invalidFIDs) > 0 {
		if delErr := s.repo.DeleteByFIDs(finalizeCtx, invalidFIDs); delErr != nil {
			slog.Warn("failed to bulk delete invalid installations", "count", len(invalidFIDs), "err", delErr)
		} else {
			slog.Info("Deleted invalid installations", "count", len(invalidFIDs))
		}
	}
	if sendErr != nil {
		return nil, sendErr
	}
	return enabledIDs, nil
}

// excludeStaleTargets re-reads the recipients' installations right before the FCM call
// and drops any target whose InstallationID generation was deleted or transferred to
// another account after the first lookup (including while ResumeOrCreate waited on row
// locks), mirroring the retry worker's CurrentFID check. installation_id is never
// reused, so a target is current only if the same ID still carries the same FID.
// Dropped durable claims are finalized CANCELLED so they do not sit IN_PROGRESS until
// ReclaimStale. A lookup failure leaves the claims IN_PROGRESS for ReclaimStale and the
// retry worker, as the worker does on its own lookup failure. The returned messages
// align with the live targets and carry the generation's owning account
// (fcm.DataKeyAccountID) so the client can drop a message that reaches a device after
// a later transfer.
func (s *Service) excludeStaleTargets(ctx context.Context, eventID string, accountIDs []int64, targets []delivery.Target) ([]delivery.Target, []fcm.Message, error) {
	installationMap, err := s.repo.FindByAccountIDs(ctx, accountIDs)
	if err != nil {
		return nil, nil, fmt.Errorf("re-verify installation ownership: %w", err)
	}
	type generation struct {
		id  int64
		fid string
	}
	owners := make(map[generation]int64)
	for accountID, installations := range installationMap {
		for _, i := range installations {
			owners[generation{i.ID, i.FID}] = accountID
		}
	}

	finalizeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
	defer cancel()

	live := make([]delivery.Target, 0, len(targets))
	messages := make([]fcm.Message, 0, len(targets))
	for _, t := range targets {
		if owner, ok := owners[generation{t.InstallationID, t.FID}]; ok {
			live = append(live, t)
			messages = append(messages, fcm.Message{FID: t.FID, Data: fcm.WithAccountID(eventData(eventID), owner)})
			continue
		}
		if eventID == "" {
			continue
		}
		if err := s.delivery.FinalizeCancelled(finalizeCtx, eventID, t.InstallationID, t.ClaimToken); err != nil {
			slog.Warn("failed to finalize cancelled delivery", "installationId", t.InstallationID, "err", err)
		}
	}
	return live, messages, nil
}

// eventData is the FCM data payload shared by every installation of one event; the
// ledger stores it so the retry worker resends the same eventId.
func eventData(eventID string) map[string]string {
	if eventID == "" {
		return nil
	}
	return map[string]string{fcm.DataKeyEventID: eventID}
}

func (s *Service) finalizeFailure(ctx context.Context, eventID string, target delivery.Target, errClass delivery.ErrorClass) {
	status, err := s.delivery.FinalizeFailure(ctx, eventID, target.InstallationID, target.ClaimToken, errClass, time.Now())
	if err != nil {
		slog.Warn("failed to finalize delivery failure", "errClass", errClass, "err", err)
		return
	}
	if status == delivery.StatusQuarantined {
		monitoring.RecordFCMDeliveryQuarantined(string(errClass))
	}
}
