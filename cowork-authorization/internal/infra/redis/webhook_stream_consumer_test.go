package redisinfra

import (
	"errors"
	"fmt"
	"testing"

	"github.com/cowork/authorization/internal/domain"
)

func TestStreamErrorKind(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want string
	}{
		{"invalid payload is discarded", domain.ErrInvalidWebhookPayload, "invalid"},
		{"identity conflict is discarded", domain.ErrWebhookConflict, "invalid"},
		{"expired event is discarded", domain.ErrWebhookExpired, "invalid"},
		{"future timestamp stays pending for retry", fmt.Errorf("wrap: %w", domain.ErrWebhookFuture), "processing"},
		{"unknown error stays pending", errors.New("db down"), "processing"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := streamErrorKind(tt.err); got != tt.want {
				t.Fatalf("streamErrorKind() = %q, want %q", got, tt.want)
			}
		})
	}
}
