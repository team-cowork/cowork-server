package domain

import (
	"testing"
	"time"
)

func TestRefreshTokenUnexpiredRequiresStrictlyFutureExpiry(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.August, 26, 1, 2, 3, 0, time.UTC)
	if RefreshTokenUnexpired(now.Add(-time.Nanosecond), now) {
		t.Fatal("expired token must not rotate")
	}
	if RefreshTokenUnexpired(now, now) {
		t.Fatal("token expiring exactly now must not rotate")
	}
	if !RefreshTokenUnexpired(now.Add(time.Nanosecond), now) {
		t.Fatal("strictly unexpired token must rotate")
	}
}
