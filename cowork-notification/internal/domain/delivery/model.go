// Package delivery tracks the durable, per-(event, installation) FCM delivery
// ledger that lets a retryable individual send failure be retried without
// resending to installations that already reached a terminal outcome.
package delivery

import "time"

type Status string

const (
	// StatusPendingRetry: not yet attempted, or a retryable/unclassified failure is
	// waiting for NextAttemptAt before its next send.
	StatusPendingRetry Status = "PENDING_RETRY"
	// StatusInProgress: claimed by the retry worker; being sent right now.
	StatusInProgress Status = "IN_PROGRESS"
	// StatusSuccess: FCM accepted the FID. Terminal.
	StatusSuccess Status = "SUCCESS"
	// StatusInvalid: the FID itself is unusable and was deleted from tb_device_installation. Terminal.
	StatusInvalid Status = "INVALID"
	// StatusQuarantined: exceeded its retry ceiling without succeeding. Terminal.
	StatusQuarantined Status = "QUARANTINED"
	// StatusCancelled: the underlying installation was deleted or replaced before a
	// queued retry ran. Terminal.
	StatusCancelled Status = "CANCELLED"
)

// ErrorClass records which retry ceiling applies to a non-terminal row.
type ErrorClass string

const (
	ErrorClassRetryable    ErrorClass = "RETRYABLE"
	ErrorClassUnclassified ErrorClass = "UNCLASSIFIED"
)

// Target is one Firebase installation a caller wants to (re)send a notification to.
// ClaimToken is set whenever the repository just claimed (or created) the row as
// IN_PROGRESS for this attempt; the caller must echo it back to Finalize* so a row
// reclaimed by someone else in the meantime (stale-reclaim, or a slower duplicate
// attempt) cannot have its outcome overwritten by this attempt.
type Target struct {
	InstallationID int64
	FID            string
	ClaimToken     string
}

// Record is one durable (EventID, InstallationID) delivery row.
type Record struct {
	ID             int64
	EventID        string
	InstallationID int64
	FID            string
	Title          string
	Body           string
	Data           map[string]string
	Status         Status
	AttemptCount   int
	NextAttemptAt  *time.Time
	LastErrorClass *ErrorClass
	// ClaimToken is set while Status is IN_PROGRESS; the worker must echo it back to
	// Finalize* (see Target.ClaimToken).
	ClaimToken string
	CreatedAt  time.Time
	UpdatedAt  time.Time
}
