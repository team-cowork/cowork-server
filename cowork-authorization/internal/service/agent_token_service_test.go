package service

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"gorm.io/gorm"

	"github.com/cowork/authorization/internal/domain"
)

type fakeAgentTokenStore struct {
	tokens   map[string]*domain.AgentToken
	markedID int64
}

func (s *fakeAgentTokenStore) Create(_ context.Context, token *domain.AgentToken) error {
	token.ID = int64(len(s.tokens) + 1)
	s.tokens[token.TokenHash] = token
	return nil
}

func (s *fakeAgentTokenStore) ListByUser(context.Context, int64) ([]domain.AgentToken, error) {
	return nil, nil
}

func (s *fakeAgentTokenStore) FindByHash(_ context.Context, hash string) (*domain.AgentToken, error) {
	token, ok := s.tokens[hash]
	if !ok {
		return nil, gorm.ErrRecordNotFound
	}
	return token, nil
}

func (s *fakeAgentTokenStore) Revoke(context.Context, int64, int64, time.Time) (bool, error) {
	return false, nil
}

func (s *fakeAgentTokenStore) MarkUsed(_ context.Context, id int64, _ time.Time) error {
	s.markedID = id
	return nil
}

func newUnitAgentTokenService(now time.Time) (*AgentTokenService, *fakeAgentTokenStore) {
	store := &fakeAgentTokenStore{tokens: map[string]*domain.AgentToken{}}
	svc := NewAgentTokenService(store, newUnitTokenService(15*time.Minute, time.Hour))
	svc.now = func() time.Time { return now }
	return svc, store
}

func TestIssueAgentTokenStoresOnlyHashAndNormalizedScopes(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.November, 26, 1, 0, 0, 0, time.UTC)
	svc, store := newUnitAgentTokenService(now)
	issued, err := svc.Issue(context.Background(), 7, " claude ", []string{"chat:write", "chat:read"}, 30)
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if !strings.HasPrefix(issued.Token, AgentTokenPrefix) {
		t.Fatalf("token = %q, want prefix %q", issued.Token, AgentTokenPrefix)
	}
	stored, ok := store.tokens[HashToken(issued.Token)]
	if !ok {
		t.Fatal("store does not hold the token hash")
	}
	if stored.TokenHash == issued.Token {
		t.Fatal("store holds the raw token")
	}
	if stored.Name != "claude" || stored.Scopes != "chat:read chat:write" || stored.UserID != 7 {
		t.Fatalf("stored = %+v", stored)
	}
	if want := now.AddDate(0, 0, 30); !stored.ExpiresAt.Equal(want) {
		t.Fatalf("ExpiresAt = %s, want %s", stored.ExpiresAt, want)
	}
}

func TestIssueAgentTokenRejectsInvalidRequests(t *testing.T) {
	t.Parallel()

	svc, store := newUnitAgentTokenService(time.Now())
	tests := []struct {
		name   string
		label  string
		scopes []string
		days   int
	}{
		{name: "blank name", label: " ", scopes: []string{"chat:read"}, days: 1},
		{name: "long name", label: strings.Repeat("가", maxAgentTokenNameRuneCount+1), scopes: []string{"chat:read"}, days: 1},
		{name: "no scope", label: "agent", scopes: nil, days: 1},
		{name: "unknown scope", label: "agent", scopes: []string{"team:write"}, days: 1},
		{name: "zero lifetime", label: "agent", scopes: []string{"chat:read"}, days: 0},
		{name: "long lifetime", label: "agent", scopes: []string{"chat:read"}, days: MaxAgentTokenLifetimeDays + 1},
	}
	for _, test := range tests {
		_, err := svc.Issue(context.Background(), 1, test.label, test.scopes, test.days)
		if !errors.Is(err, ErrInvalidAgentTokenRequest) {
			t.Errorf("%s: Issue() error = %v, want ErrInvalidAgentTokenRequest", test.name, err)
		}
	}
	if len(store.tokens) != 0 {
		t.Fatalf("store has %d tokens, want 0", len(store.tokens))
	}
}

func TestExchangeAgentTokenRejectsUnknownRevokedAndExpiredTokens(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.November, 26, 1, 0, 0, 0, time.UTC)
	svc, store := newUnitAgentTokenService(now)
	revokedAt := now.Add(-time.Second)
	store.tokens[HashToken(AgentTokenPrefix+"revoked")] = &domain.AgentToken{
		ID: 1, ExpiresAt: now.Add(time.Hour), RevokedAt: &revokedAt,
	}
	store.tokens[HashToken(AgentTokenPrefix+"expired")] = &domain.AgentToken{ID: 2, ExpiresAt: now}

	for _, raw := range []string{"", "no-prefix", AgentTokenPrefix + "unknown", AgentTokenPrefix + "revoked", AgentTokenPrefix + "expired"} {
		if _, err := svc.Exchange(context.Background(), raw); !errors.Is(err, ErrAgentTokenRejected) {
			t.Errorf("Exchange(%q) error = %v, want ErrAgentTokenRejected", raw, err)
		}
	}
	if store.markedID != 0 {
		t.Fatalf("rejected exchange recorded use of token %d", store.markedID)
	}
}

func TestExchangeAgentTokenSignsScopedJWTThatNeverOutlivesToken(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.November, 26, 1, 0, 0, 0, time.UTC)
	tests := []struct {
		name          string
		tokenExpires  time.Time
		wantExpiresIn int
	}{
		{name: "access lifetime", tokenExpires: now.Add(time.Hour), wantExpiresIn: 15 * 60},
		{name: "capped by token", tokenExpires: now.Add(5 * time.Minute), wantExpiresIn: 5 * 60},
	}
	for _, test := range tests {
		svc, store := newUnitAgentTokenService(now)
		raw := AgentTokenPrefix + "active"
		store.tokens[HashToken(raw)] = &domain.AgentToken{
			ID: 9, UserID: 42, Scopes: "chat:read chat:write", ExpiresAt: test.tokenExpires,
		}

		access, err := svc.Exchange(context.Background(), raw)
		if err != nil {
			t.Fatalf("%s: Exchange() error = %v", test.name, err)
		}
		if access.ExpiresIn != test.wantExpiresIn || access.TokenType != "Bearer" {
			t.Errorf("%s: access = %+v, want expires_in %d", test.name, access, test.wantExpiresIn)
		}
		if store.markedID != 9 {
			t.Errorf("%s: markedID = %d, want 9", test.name, store.markedID)
		}

		claims := &Claims{}
		_, err = jwt.NewParser(jwt.WithTimeFunc(func() time.Time { return now })).
			ParseWithClaims(access.AccessToken, claims, func(*jwt.Token) (any, error) {
				return []byte("unit-test-secret"), nil
			})
		if err != nil {
			t.Fatalf("%s: parse exchanged JWT: %v", test.name, err)
		}
		if claims.Subject != "42" || claims.TokenUse != domain.AgentTokenUse || claims.Scope != "chat:read chat:write" {
			t.Errorf("%s: claims = %+v", test.name, claims)
		}
	}
}

func TestLoginAccessTokenOmitsAgentClaims(t *testing.T) {
	t.Parallel()

	signed, err := newUnitTokenService(time.Minute, time.Hour).GenerateAccessToken(1, "user@example.com", "")
	if err != nil {
		t.Fatalf("GenerateAccessToken() error = %v", err)
	}
	claims := jwt.MapClaims{}
	if _, _, err := jwt.NewParser().ParseUnverified(signed, claims); err != nil {
		t.Fatalf("parse login JWT: %v", err)
	}
	for _, name := range []string{"token_use", "scope"} {
		if _, ok := claims[name]; ok {
			t.Errorf("login JWT has %s claim", name)
		}
	}
}
