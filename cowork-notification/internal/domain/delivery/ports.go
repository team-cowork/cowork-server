package delivery

import (
	"context"
	"time"
)

// Repository persists the (eventId, deviceTokenId) delivery ledger described in
// docs/todo/items/33-reliability/fcm-partial-failure-retry.md.
type Repository interface {
	// ResumeOrCreate durably claims every target token under eventID before the first
	// FCM call, then returns only the subset that still needs an attempt: newly
	// inserted rows, and any row a previous crash or Kafka redelivery left eligible to
	// retry now. Every returned TargetToken is transitioned to IN_PROGRESS as part of
	// this call (with a fresh ClaimToken) so the retry worker's ClaimDue cannot also
	// pick it up during the synchronous FCM call that follows. Tokens whose row already
	// reached a terminal status, is currently IN_PROGRESS, or is still backing off are
	// excluded so a redelivered message never resends to them out of turn.
	ResumeOrCreate(
		ctx context.Context,
		eventID string,
		targets []TargetToken,
		title, body string,
		data map[string]string,
	) ([]TargetToken, error)

	// FinalizeSuccess marks one token's delivery for eventID as SUCCESS, clearing the
	// stored notification content. claimToken must match the row's current claim (from
	// ResumeOrCreate or ClaimDue); a mismatch means the row was reclaimed by someone
	// else in the meantime and this call is a no-op rather than an overwrite.
	FinalizeSuccess(ctx context.Context, eventID string, deviceTokenID int64, claimToken string) error

	// FinalizeInvalid marks one token's delivery for eventID as INVALID, clearing the
	// stored notification content. The caller is still responsible for deleting the
	// token from the device token table. See FinalizeSuccess for claimToken fencing.
	FinalizeInvalid(ctx context.Context, eventID string, deviceTokenID int64, claimToken string) error

	// FinalizeFailure records a retryable or unclassified failure, applying the policy
	// in policy.go to decide between PENDING_RETRY (with backoff) and QUARANTINED —
	// clearing the stored notification content in the latter case — and returns which
	// one it landed on so the caller can observe quarantines. See FinalizeSuccess for
	// claimToken fencing.
	FinalizeFailure(ctx context.Context, eventID string, deviceTokenID int64, claimToken string, errClass ErrorClass, now time.Time) (Status, error)

	// ClaimDue locks and transitions up to limit due PENDING_RETRY rows to IN_PROGRESS
	// with a fresh ClaimToken, returning them for the retry worker to attempt.
	// Serialized across replicas by the implementation so the same row is never claimed
	// twice concurrently.
	ClaimDue(ctx context.Context, limit int, now time.Time) ([]Record, error)

	// FinalizeCancelled marks a claimed row CANCELLED, clearing the stored notification
	// content, because its device token was deleted or replaced before the retry ran.
	// See FinalizeSuccess for claimToken fencing.
	FinalizeCancelled(ctx context.Context, id int64, claimToken string) error

	// ReclaimStale returns IN_PROGRESS rows to PENDING_RETRY (eligible immediately),
	// clearing their ClaimToken, when they have sat claimed longer than staleThreshold —
	// covering a worker crash, or a worker so slow its claim batch outlives
	// staleThreshold, between ClaimDue/ResumeOrCreate and the matching Finalize* call.
	// Clearing ClaimToken ensures a Finalize* call that eventually does arrive for the
	// original claim no longer matches and safely no-ops. Returns the number reclaimed.
	ReclaimStale(ctx context.Context, staleThreshold time.Duration, now time.Time) (int64, error)

	// PurgeTerminal deletes terminal rows (SUCCESS, INVALID, QUARANTINED, CANCELLED)
	// last updated before olderThan, bounding the table's growth. Returns the number
	// purged.
	PurgeTerminal(ctx context.Context, olderThan time.Time) (int64, error)

	// CountPending reports the current PENDING_RETRY/IN_PROGRESS backlog size, for metrics.
	CountPending(ctx context.Context) (int64, error)
}
