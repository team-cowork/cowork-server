package domain

import (
	"errors"
	"testing"
	"time"
)

func TestWebhookAcceptanceWindow(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 9, 22, 0, 0, 0, 0, time.UTC)
	for _, test := range []struct {
		name     string
		occurred time.Time
		want     error
	}{
		{"current event", now, nil},
		{"within thirty days", now.Add(-WebhookRetention).Add(time.Nanosecond), nil},
		{"exactly thirty days is expired", now.Add(-WebhookRetention), ErrWebhookExpired},
		{"older events stay expired", now.Add(-WebhookRetention - time.Second), ErrWebhookExpired},
		{"five minute clock skew is allowed", now.Add(WebhookFutureSkew), nil},
		{"excessive future timestamp is rejected", now.Add(WebhookFutureSkew + time.Nanosecond), ErrWebhookFuture},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			if err := ValidateWebhookWindow(test.occurred, now); !errors.Is(err, test.want) {
				t.Fatalf("acceptance error = %v, want %v", err, test.want)
			}
		})
	}
}
