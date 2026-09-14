// Package delivery tracks the durable, per-(event, device token) FCM delivery
// ledger that lets a retryable individual send failure be retried without
// resending to tokens that already reached a terminal outcome. See
// docs/todo/items/33-reliability/fcm-partial-failure-retry.md for the contract.
package delivery

import "time"

type Status string

const (
	// StatusPendingRetry: not yet attempted, or a retryable/unclassified failure is
	// waiting for NextAttemptAt before its next send.
	StatusPendingRetry Status = "PENDING_RETRY"
	// StatusInProgress: claimed by the retry worker; being sent right now.
	StatusInProgress Status = "IN_PROGRESS"
	// StatusSuccess: FCM accepted the token. Terminal.
	StatusSuccess Status = "SUCCESS"
	// StatusInvalid: the token itself is unusable and was deleted from tb_device_token. Terminal.
	StatusInvalid Status = "INVALID"
	// StatusQuarantined: exceeded its retry ceiling without succeeding. Terminal.
	StatusQuarantined Status = "QUARANTINED"
	// StatusCancelled: the underlying device token was deleted or replaced before a
	// queued retry ran. Terminal.
	StatusCancelled Status = "CANCELLED"
)

// ErrorClass records which retry ceiling applies to a non-terminal row.
type ErrorClass string

const (
	ErrorClassRetryable    ErrorClass = "RETRYABLE"
	ErrorClassUnclassified ErrorClass = "UNCLASSIFIED"
)

// TargetToken is one device token a caller wants to (re)send a notification to.
type TargetToken struct {
	DeviceTokenID int64
	Token         string
}

// Record is one durable (EventID, DeviceTokenID) delivery row.
type Record struct {
	ID             int64
	EventID        string
	DeviceTokenID  int64
	Token          string
	Title          string
	Body           string
	Data           map[string]string
	Status         Status
	AttemptCount   int
	NextAttemptAt  *time.Time
	LastErrorClass *ErrorClass
	CreatedAt      time.Time
	UpdatedAt      time.Time
}
