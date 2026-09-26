package domain

import (
	"errors"
	"time"
)

const (
	WebhookRetention  = 30 * 24 * time.Hour
	WebhookFutureSkew = 5 * time.Minute
)

var (
	ErrInvalidWebhookPayload = errors.New("invalid webhook payload")
	ErrWebhookExpired        = errors.New("webhook event expired")
	ErrWebhookFuture         = errors.New("webhook timestamp is in the future")
	ErrWebhookConflict       = errors.New("webhook event identity conflict")
)

type WebhookResult string

const (
	WebhookAccepted  WebhookResult = "accepted"
	WebhookDuplicate WebhookResult = "duplicate"
	WebhookIgnored   WebhookResult = "ignored"
)

// WebhookBatch contains only fully validated, serialized messages. Its identity
// and original timestamp remain unchanged through delivery retries.
type WebhookBatch struct {
	EventID     string
	EventType   string
	OccurredAt  time.Time
	PayloadHash [32]byte
	Messages    []WebhookMessage
}

type WebhookMessage struct {
	Index   int64
	Key     string
	Payload []byte
}

// ValidateWebhookWindow uses the database clock supplied by the accepting
// transaction, so replicas and the retention worker share the same authority.
func ValidateWebhookWindow(occurredAt, now time.Time) error {
	if !occurredAt.After(now.Add(-WebhookRetention)) {
		return ErrWebhookExpired
	}
	if occurredAt.After(now.Add(WebhookFutureSkew)) {
		return ErrWebhookFuture
	}
	return nil
}

func WebhookExpiresAt(acceptedAt, occurredAt time.Time) time.Time {
	if occurredAt.After(acceptedAt) {
		acceptedAt = occurredAt
	}
	// MySQL DATETIME(6) must never round expiration down into the acceptance window.
	expires := acceptedAt.Add(WebhookRetention)
	rounded := expires.Truncate(time.Microsecond)
	if rounded.Before(expires) {
		rounded = rounded.Add(time.Microsecond)
	}
	return rounded.UTC()
}
