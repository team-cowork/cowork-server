package domain

import (
	"testing"
	"time"
)

func TestDecidePresenceTransitionUsesLastUnexpiredSessionSemantics(t *testing.T) {
	t.Parallel()
	storedAt := time.Date(2026, 8, 26, 1, 2, 3, 456788000, time.UTC)
	incomingAt := storedAt.Add(time.Microsecond)

	tests := []struct {
		name           string
		currentStatus  string
		currentAt      time.Time
		activeSessions int64
		incomingAt     time.Time
		wantStatus     string
		wantChanged    bool
	}{
		{
			name:           "first active session becomes online",
			currentStatus:  PresenceOffline,
			currentAt:      storedAt,
			activeSessions: 1,
			incomingAt:     incomingAt,
			wantStatus:     PresenceOnline,
			wantChanged:    true,
		},
		{
			name:           "additional active session stays online without event",
			currentStatus:  PresenceOnline,
			currentAt:      storedAt,
			activeSessions: 2,
			incomingAt:     incomingAt,
			wantStatus:     PresenceOnline,
			wantChanged:    false,
		},
		{
			name:           "one remaining unexpired session stays online",
			currentStatus:  PresenceOnline,
			currentAt:      storedAt,
			activeSessions: 1,
			incomingAt:     incomingAt,
			wantStatus:     PresenceOnline,
			wantChanged:    false,
		},
		{
			name:           "last unexpired session removal becomes offline",
			currentStatus:  PresenceOnline,
			currentAt:      storedAt,
			activeSessions: 0,
			incomingAt:     incomingAt,
			wantStatus:     PresenceOffline,
			wantChanged:    true,
		},
		{
			name:           "repeated cleanup keeps durable offline row without event",
			currentStatus:  PresenceOffline,
			currentAt:      storedAt,
			activeSessions: 0,
			incomingAt:     incomingAt,
			wantStatus:     PresenceOffline,
			wantChanged:    false,
		},
		{
			name:           "stored count drift is reconciled from active sessions",
			currentStatus:  PresenceOffline,
			currentAt:      storedAt,
			activeSessions: 3,
			incomingAt:     incomingAt,
			wantStatus:     PresenceOnline,
			wantChanged:    true,
		},
		{
			name:           "offline wins an equal timestamp against online",
			currentStatus:  PresenceOnline,
			currentAt:      storedAt,
			activeSessions: 0,
			incomingAt:     storedAt,
			wantStatus:     PresenceOffline,
			wantChanged:    true,
		},
		{
			name:           "online cannot replace offline at an equal timestamp",
			currentStatus:  PresenceOffline,
			currentAt:      storedAt,
			activeSessions: 1,
			incomingAt:     storedAt,
			wantStatus:     PresenceOffline,
			wantChanged:    false,
		},
		{
			name:           "stale transition cannot replace newer durable state",
			currentStatus:  PresenceOffline,
			currentAt:      storedAt,
			activeSessions: 1,
			incomingAt:     storedAt.Add(-time.Microsecond),
			wantStatus:     PresenceOffline,
			wantChanged:    false,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			decision := DecidePresenceTransition(
				test.currentStatus,
				test.currentAt,
				test.activeSessions,
				test.incomingAt,
			)
			if decision.Status != test.wantStatus {
				t.Fatalf("status = %q, want %q", decision.Status, test.wantStatus)
			}
			if decision.Changed != test.wantChanged {
				t.Fatalf("changed = %v, want %v", decision.Changed, test.wantChanged)
			}
		})
	}
}

func TestPresenceTransitionOccurredAtSerializesSameMicrosecondMutations(t *testing.T) {
	t.Parallel()

	storedAt := time.Date(2026, 8, 26, 1, 2, 3, 456789000, time.UTC)
	tests := []struct {
		name           string
		currentStatus  string
		activeSessions int64
		requestedAt    time.Time
		want           time.Time
	}{
		{
			name:           "same-microsecond login after offline receives next logical version",
			currentStatus:  PresenceOffline,
			activeSessions: 1,
			requestedAt:    storedAt,
			want:           storedAt.Add(time.Microsecond),
		},
		{
			name:           "same-microsecond final revoke receives next logical version",
			currentStatus:  PresenceOnline,
			activeSessions: 0,
			requestedAt:    storedAt,
			want:           storedAt.Add(time.Microsecond),
		},
		{
			name:           "backward wall clock cannot reverse serialized source order",
			currentStatus:  PresenceOffline,
			activeSessions: 1,
			requestedAt:    storedAt.Add(-time.Hour),
			want:           storedAt.Add(time.Microsecond),
		},
		{
			name:           "unchanged status keeps its stored mutation version",
			currentStatus:  PresenceOnline,
			activeSessions: 2,
			requestedAt:    storedAt,
			want:           storedAt,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			got := PresenceTransitionOccurredAt(
				test.currentStatus,
				storedAt,
				test.activeSessions,
				test.requestedAt,
			)
			if !got.Equal(test.want) {
				t.Fatalf("PresenceTransitionOccurredAt() = %s, want %s", got, test.want)
			}
		})
	}
}
