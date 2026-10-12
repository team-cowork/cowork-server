package delivery

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/cowork/cowork-notification/internal/infra/fcm"
	"github.com/cowork/cowork-notification/internal/monitoring"
)

const (
	workerInterval       = 15 * time.Second
	workerBatchSize      = 100
	staleInProgressAfter = 2 * time.Minute
	// attemptConcurrency bounds how many claimed rows Worker.tick sends to FCM at
	// once. Attempting workerBatchSize rows one at a time is what let a slow FCM
	// response push the tail of a batch past staleInProgressAfter, at which point
	// another replica's ReclaimStale reverts a row this worker was still holding and
	// a later ClaimDue can pick it up a second time. Fanning attempts out keeps the
	// whole claimed batch's wall-clock time well under that threshold.
	attemptConcurrency = 20

	// fcmSendTimeout bounds a single Send call. The Firebase Admin SDK's default HTTP
	// client retries 503s and network errors up to 4 times, honoring Retry-After for as
	// long as 2 minutes, so an unbounded ctx lets one attempt approach
	// staleInProgressAfter on its own — another replica's ReclaimStale would then revert
	// a row this worker still holds, and a later ClaimDue could send it again.
	fcmSendTimeout = 30 * time.Second
	// finalizeTimeout bounds the detached context used to record an FCM result that has
	// already happened. It must survive ctx (the Kafka consumer's lease) being cancelled
	// or expiring, otherwise an FID FCM already accepted could be resent by the retry
	// worker before the row is marked done.
	finalizeTimeout = 30 * time.Second

	purgeInterval  = 10 * time.Minute
	purgeRetention = 7 * 24 * time.Hour
)

// InstallationVerifier answers whether an installation row is still current, so the
// retry worker can cancel a queued retry when the installation was deleted or
// reassigned.
type InstallationVerifier interface {
	// CurrentFID returns the live FID and owning account for installationID and true,
	// or ("", 0, false) if the installation row no longer exists.
	CurrentFID(ctx context.Context, installationID int64) (fid string, accountID int64, ok bool, err error)
}

// InvalidInstallationHandler deletes an FID FCM reports as permanently unusable.
type InvalidInstallationHandler interface {
	DeleteInvalidFID(ctx context.Context, fid string) error
}

type fcmSender interface {
	Send(ctx context.Context, messages []fcm.Message, title, body string) ([]fcm.Result, error)
}

// Worker periodically resends PENDING_RETRY rows whose backoff has elapsed. Run must
// only ever be driven by one goroutine — tick's throttled purge state assumes it is
// never called concurrently with itself.
type Worker struct {
	repo          Repository
	installations InstallationVerifier
	invalid       InvalidInstallationHandler
	fcm           fcmSender
	lastPurge     time.Time
}

func NewWorker(repo Repository, installations InstallationVerifier, invalid InvalidInstallationHandler, sender fcmSender) *Worker {
	return &Worker{repo: repo, installations: installations, invalid: invalid, fcm: sender}
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
	w.attemptAll(ctx, due)

	if pending, err := w.repo.CountPending(ctx); err != nil {
		if ctx.Err() == nil {
			slog.Error("fcm retry worker: count pending failed", "err", err)
		}
	} else {
		monitoring.SetFCMDeliveryPending(float64(pending))
	}

	w.purgeIfDue(ctx)
}

// attemptAll fans claimed rows out across a bounded worker pool so the whole batch's
// wall-clock time cannot approach staleInProgressAfter even when FCM is slow. Each
// row belongs to a different installation, so attempts share no mutable state.
func (w *Worker) attemptAll(ctx context.Context, due []Record) {
	if len(due) == 0 {
		return
	}
	sem := make(chan struct{}, attemptConcurrency)
	var wg sync.WaitGroup
	for _, rec := range due {
		wg.Add(1)
		sem <- struct{}{}
		go func(rec Record) {
			defer wg.Done()
			defer func() { <-sem }()
			w.attempt(ctx, rec)
		}(rec)
	}
	wg.Wait()
}

func (w *Worker) purgeIfDue(ctx context.Context) {
	now := time.Now()
	if now.Sub(w.lastPurge) < purgeInterval {
		return
	}
	w.lastPurge = now
	purged, err := w.repo.PurgeTerminal(ctx, now.Add(-purgeRetention))
	if err != nil {
		if ctx.Err() == nil {
			slog.Error("fcm retry worker: purge terminal rows failed", "err", err)
		}
		return
	}
	if purged > 0 {
		slog.Info("fcm retry worker: purged terminal delivery rows", "count", purged)
	}
}

func (w *Worker) attempt(ctx context.Context, rec Record) {
	currentFID, accountID, ok, err := w.installations.CurrentFID(ctx, rec.InstallationID)
	if err != nil {
		slog.Error("fcm retry worker: installation lookup failed", "installationId", rec.InstallationID, "err", err)
		return
	}
	if !ok || currentFID != rec.FID {
		// The installation was deleted, or replaced (installation_id is never reused, so
		// a mismatch means the current row for this id no longer holds the FID the
		// claimed delivery was for) — the row is stale relative to current state.
		finalizeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), finalizeTimeout)
		defer cancel()
		if err := w.repo.FinalizeCancelled(finalizeCtx, rec.EventID, rec.InstallationID, rec.ClaimToken); err != nil {
			slog.Error("fcm retry worker: finalize cancelled failed", "id", rec.ID, "err", err)
		}
		return
	}

	sendCtx, cancel := context.WithTimeout(ctx, fcmSendTimeout)
	msg := fcm.Message{FID: rec.FID, Data: fcm.WithAccountID(rec.Data, accountID)}
	results, err := w.fcm.Send(sendCtx, []fcm.Message{msg}, rec.Title, rec.Body)
	cancel()
	if err != nil || len(results) == 0 {
		// ctx cancellation (shutdown), the fcmSendTimeout bound, or an empty result for a
		// non-empty input, which Send's contract does not produce. Leave the row
		// IN_PROGRESS; ReclaimStale will return it to PENDING_RETRY on a later tick.
		if err != nil && ctx.Err() == nil {
			slog.Error("fcm retry worker: send failed", "installationId", rec.InstallationID, "err", err)
		}
		return
	}

	finalizeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), finalizeTimeout)
	defer cancel()

	outcome := results[0].Outcome
	monitoring.RecordFCMDeliveryOutcome(string(outcome), "retry")
	switch outcome {
	case fcm.OutcomeSuccess:
		if err := w.repo.FinalizeSuccess(finalizeCtx, rec.EventID, rec.InstallationID, rec.ClaimToken); err != nil {
			slog.Error("fcm retry worker: finalize success failed", "id", rec.ID, "err", err)
		}
	case fcm.OutcomeInvalid:
		if err := w.repo.FinalizeInvalid(finalizeCtx, rec.EventID, rec.InstallationID, rec.ClaimToken); err != nil {
			slog.Error("fcm retry worker: finalize invalid failed", "id", rec.ID, "err", err)
		}
		if err := w.invalid.DeleteInvalidFID(finalizeCtx, rec.FID); err != nil {
			slog.Warn("fcm retry worker: delete invalid installation failed", "err", err)
		} else {
			slog.Info("fcm retry worker: deleted invalid installation", "installationId", rec.InstallationID)
		}
	case fcm.OutcomeUnclassified:
		w.finalizeFailure(finalizeCtx, rec, ErrorClassUnclassified)
	default: // fcm.OutcomeRetryable
		w.finalizeFailure(finalizeCtx, rec, ErrorClassRetryable)
	}
}

func (w *Worker) finalizeFailure(ctx context.Context, rec Record, errClass ErrorClass) {
	status, err := w.repo.FinalizeFailure(ctx, rec.EventID, rec.InstallationID, rec.ClaimToken, errClass, time.Now())
	if err != nil {
		slog.Error("fcm retry worker: finalize failure failed", "id", rec.ID, "err", err)
		return
	}
	if status == StatusQuarantined {
		monitoring.RecordFCMDeliveryQuarantined(string(errClass))
	}
}
