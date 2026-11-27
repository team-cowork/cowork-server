package domain

import (
	"errors"
	"slices"
	"testing"
	"time"
)

func TestAgentTokenExchangeableRejectsRevokedAndExpiredTokens(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.November, 26, 1, 2, 3, 0, time.UTC)
	revokedAt := now.Add(-time.Minute)
	tests := []struct {
		name  string
		token AgentToken
		want  bool
	}{
		{name: "active", token: AgentToken{ExpiresAt: now.Add(time.Nanosecond)}, want: true},
		{name: "expiring now", token: AgentToken{ExpiresAt: now}, want: false},
		{name: "expired", token: AgentToken{ExpiresAt: now.Add(-time.Hour)}, want: false},
		{name: "revoked", token: AgentToken{ExpiresAt: now.Add(time.Hour), RevokedAt: &revokedAt}, want: false},
	}
	for _, test := range tests {
		if got := test.token.Exchangeable(now); got != test.want {
			t.Errorf("%s: Exchangeable() = %v, want %v", test.name, got, test.want)
		}
	}
}

func TestNormalizeAgentScopesSortsAndDeduplicates(t *testing.T) {
	t.Parallel()

	got, err := NormalizeAgentScopes([]string{"chat:write", "workspace:read", "chat:read", "chat:write"})
	if err != nil {
		t.Fatalf("NormalizeAgentScopes() error = %v", err)
	}
	if want := []string{"chat:read", "chat:write", "workspace:read"}; !slices.Equal(got, want) {
		t.Fatalf("NormalizeAgentScopes() = %v, want %v", got, want)
	}
}

func TestNormalizeAgentScopesRejectsEmptyAndUnknownScopes(t *testing.T) {
	t.Parallel()

	for _, scopes := range [][]string{nil, {}, {"chat:read", "admin"}, {""}, {"CHAT:READ"}} {
		if _, err := NormalizeAgentScopes(scopes); !errors.Is(err, ErrInvalidAgentScope) {
			t.Errorf("NormalizeAgentScopes(%q) error = %v, want ErrInvalidAgentScope", scopes, err)
		}
	}
}
