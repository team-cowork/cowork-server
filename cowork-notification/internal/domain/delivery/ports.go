package delivery

import (
	"context"
	"time"
)

// Repository persists the (eventId, deviceTokenId) delivery ledger described in
// docs/todo/items/33-reliability/fcm-partial-failure-retry.md.
type Repository interface {
	// ResumeOrCreate durably records intent to deliver to every target token under
	// eventID before the first FCM call, then returns only the subset that still needs
	// an attempt: newly inserted rows, and any row a previous crash or Kafka redelivery
	// left non-terminal. Tokens whose row already reached SUCCESS, INVALID, QUARANTINED,
	// or CANCELLED are excluded so a redelivered message never resends to them.
	ResumeOrCreate(
		ctx context.Context,
		eventID string,
		targets []TargetToken,
		title, body string,
		data map[string]string,
	) ([]TargetToken, error)

	// FinalizeSuccess marks one token's delivery for eventID as SUCCESS.
	FinalizeSuccess(ctx context.Context, eventID string, deviceTokenID int64) error

	// FinalizeInvalid marks one token's delivery for eventID as INVALID. The caller is
	// still responsible for deleting the token from the device token table.
	FinalizeInvalid(ctx context.Context, eventID string, deviceTokenID int64) error

	// FinalizeFailure records a retryable or unclassified failure, applying the policy
	// in policy.go to decide between PENDING_RETRY (with backoff) and QUARANTINED, and
	// returns which one it landed on so the caller can observe quarantines.
	FinalizeFailure(ctx context.Context, eventID string, deviceTokenID int64, errClass ErrorClass, now time.Time) (Status, error)

	// ClaimDue locks and transitions up to limit due PENDING_RETRY rows to IN_PROGRESS,
	// returning them for the retry worker to attempt. Serialized across replicas by the
	// implementation so the same row is never claimed twice concurrently.
	ClaimDue(ctx context.Context, limit int, now time.Time) ([]Record, error)

	// FinalizeCancelled marks a claimed row CANCELLED because its device token was
	// deleted or replaced before the retry ran.
	FinalizeCancelled(ctx context.Context, id int64) error

	// ReclaimStale returns IN_PROGRESS rows to PENDING_RETRY (eligible immediately) when
	// they have sat claimed longer than staleThreshold, covering a worker crash between
	// ClaimDue and the matching Finalize* call. Returns the number of rows reclaimed.
	ReclaimStale(ctx context.Context, staleThreshold time.Duration, now time.Time) (int64, error)

	// CountPending reports the current PENDING_RETRY backlog size, for metrics.
	CountPending(ctx context.Context) (int64, error)
}
