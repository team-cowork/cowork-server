package delivery

import (
	"context"
	"log/slog"
	"time"

	"github.com/cowork/cowork-notification/internal/infra/fcm"
	"github.com/cowork/cowork-notification/internal/monitoring"
)

const (
	workerInterval       = 15 * time.Second
	workerBatchSize      = 100
	staleInProgressAfter = 2 * time.Minute
)

// TokenVerifier answers whether a device token row is still current, so the retry
// worker can cancel a queued retry when the token was deleted or reassigned.
type TokenVerifier interface {
	// CurrentToken returns the live token string for deviceTokenID and true, or
	// ("", false) if the device token row no longer exists.
	CurrentToken(ctx context.Context, deviceTokenID int64) (token string, ok bool, err error)
}

// InvalidTokenHandler deletes a token FCM reports as permanently unusable.
type InvalidTokenHandler interface {
	DeleteInvalidToken(ctx context.Context, token string) error
}

type fcmSender interface {
	Send(ctx context.Context, tokens []string, title, body string, data map[string]string) ([]fcm.TokenResult, error)
}

// Worker periodically resends PENDING_RETRY rows whose backoff has elapsed.
type Worker struct {
	repo    Repository
	tokens  TokenVerifier
	invalid InvalidTokenHandler
	fcm     fcmSender
}

func NewWorker(repo Repository, tokens TokenVerifier, invalid InvalidTokenHandler, sender fcmSender) *Worker {
	return &Worker{repo: repo, tokens: tokens, invalid: invalid, fcm: sender}
}

func (w *Worker) Run(ctx context.Context) {
	ticker := time.NewTicker(workerInterval)
	defer ticker.Stop()
	for {
		w.tick(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (w *Worker) tick(ctx context.Context) {
	if reclaimed, err := w.repo.ReclaimStale(ctx, staleInProgressAfter, time.Now()); err != nil {
		if ctx.Err() == nil {
			slog.Error("fcm retry worker: reclaim stale rows failed", "err", err)
		}
	} else if reclaimed > 0 {
		slog.Warn("fcm retry worker: reclaimed stale IN_PROGRESS rows", "count", reclaimed)
	}

	due, err := w.repo.ClaimDue(ctx, workerBatchSize, time.Now())
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("fcm retry worker: claim due rows failed", "err", err)
		}
		return
	}
	for _, rec := range due {
		w.attempt(ctx, rec)
	}

	if pending, err := w.repo.CountPending(ctx); err != nil {
		if ctx.Err() == nil {
			slog.Error("fcm retry worker: count pending failed", "err", err)
		}
	} else {
		monitoring.SetFCMDeliveryPending(float64(pending))
	}
}

func (w *Worker) attempt(ctx context.Context, rec Record) {
	currentToken, ok, err := w.tokens.CurrentToken(ctx, rec.DeviceTokenID)
	if err != nil {
		slog.Error("fcm retry worker: token lookup failed", "deviceTokenId", rec.DeviceTokenID, "err", err)
		return
	}
	if !ok || currentToken != rec.Token {
		// The token was deleted, or the account re-registered a new one under the same
		// row id is impossible (device_token_id is never reused), so a mismatch means
		// the row is stale relative to the current device token state.
		if err := w.repo.FinalizeCancelled(ctx, rec.ID); err != nil {
			slog.Error("fcm retry worker: finalize cancelled failed", "id", rec.ID, "err", err)
		}
		return
	}

	results, err := w.fcm.Send(ctx, []string{rec.Token}, rec.Title, rec.Body, rec.Data)
	if err != nil || len(results) == 0 {
		// ctx cancellation (shutdown) or an empty result for a non-empty input, which
		// Send's contract does not produce. Leave the row IN_PROGRESS; ReclaimStale
		// will return it to PENDING_RETRY on a later tick.
		if err != nil && ctx.Err() == nil {
			slog.Error("fcm retry worker: send failed", "deviceTokenId", rec.DeviceTokenID, "err", err)
		}
		return
	}

	outcome := results[0].Outcome
	monitoring.RecordFCMDeliveryOutcome(string(outcome), "retry")
	switch outcome {
	case fcm.OutcomeSuccess:
		if err := w.repo.FinalizeSuccess(ctx, rec.EventID, rec.DeviceTokenID); err != nil {
			slog.Error("fcm retry worker: finalize success failed", "id", rec.ID, "err", err)
		}
	case fcm.OutcomeInvalid:
		if err := w.repo.FinalizeInvalid(ctx, rec.EventID, rec.DeviceTokenID); err != nil {
			slog.Error("fcm retry worker: finalize invalid failed", "id", rec.ID, "err", err)
		}
		if err := w.invalid.DeleteInvalidToken(ctx, rec.Token); err != nil {
			slog.Warn("fcm retry worker: delete invalid token failed", "err", err)
		}
	case fcm.OutcomeUnclassified:
		w.finalizeFailure(ctx, rec, ErrorClassUnclassified)
	default: // fcm.OutcomeRetryable
		w.finalizeFailure(ctx, rec, ErrorClassRetryable)
	}
}

func (w *Worker) finalizeFailure(ctx context.Context, rec Record, errClass ErrorClass) {
	status, err := w.repo.FinalizeFailure(ctx, rec.EventID, rec.DeviceTokenID, errClass, time.Now())
	if err != nil {
		slog.Error("fcm retry worker: finalize failure failed", "id", rec.ID, "err", err)
		return
	}
	if status == StatusQuarantined {
		monitoring.RecordFCMDeliveryQuarantined(string(errClass))
	}
}
