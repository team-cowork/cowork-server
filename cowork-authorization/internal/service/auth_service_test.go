package service

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"gorm.io/gorm"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return fn(request)
}

func jsonResponse(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Body:       io.NopCloser(strings.NewReader(body)),
		Header:     make(http.Header),
	}
}

type fakeRefreshTokenStore struct {
	createdToken   *domain.RefreshToken
	createErr      error
	rotationSource *domain.RefreshToken
	rotateErr      error
	rotateCalls    int
	revokedHash    string
	revokedUserID  int64
	revokeErr      error
	revokeCalls    int
}

func (f *fakeRefreshTokenStore) CreateSession(
	_ context.Context,
	token *domain.RefreshToken,
	_ time.Time,
	_ string,
) error {
	f.createdToken = token
	return f.createErr
}

func (f *fakeRefreshTokenStore) RotateSession(
	_ context.Context,
	_ string,
	_ string,
	_ time.Time,
) (*domain.RefreshToken, error) {
	f.rotateCalls++
	return f.rotationSource, f.rotateErr
}

func (f *fakeRefreshTokenStore) RevokeSession(
	_ context.Context,
	hash string,
	userID int64,
	_ time.Time,
	_ string,
) error {
	f.revokeCalls++
	f.revokedHash = hash
	f.revokedUserID = userID
	return f.revokeErr
}

type fakeIdentityCoordinator struct {
	userID  int64
	err     error
	calls   int
	command domain.UserIdentityCommand
}

func (f *fakeIdentityCoordinator) EnsureUser(
	_ context.Context,
	command domain.UserIdentityCommand,
) (int64, error) {
	f.calls++
	f.command = command
	return f.userID, f.err
}

func newUnitAuthService(store RefreshTokenStore, now time.Time) *AuthService {
	cfg := &config.AppConfig{
		JWTSecret:              "unit-test-secret",
		JWTAccessExpire:        30 * time.Minute,
		JWTRefreshExpire:       24 * time.Hour,
		KafkaTopicUserPresence: "user.presence.event",
	}
	service := NewAuthService(cfg, nil, store, NewTokenService(cfg), nil)
	service.now = func() time.Time { return now }
	return service
}

func TestIssueNewSession_WhenIdentityIsValid_IssuesAndStoresAHashedSession(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.August, 26, 1, 2, 3, 0, time.UTC)
	store := &fakeRefreshTokenStore{}
	service := newUnitAuthService(store, now)

	pair, err := service.issueNewSession(
		context.Background(),
		7,
		"user@example.com",
		"MEMBER",
		"STUDENT",
		"device",
	)
	if err != nil {
		t.Fatalf("issueNewSession() error = %v", err)
	}
	if pair.AccessToken == "" || pair.RefreshToken == "" {
		t.Fatal("issued token pair is empty")
	}
	if store.createdToken == nil || store.createdToken.UserID != 7 {
		t.Fatalf("created token = %+v", store.createdToken)
	}
	if store.createdToken.TokenHash == "" || store.createdToken.TokenHash == pair.RefreshToken {
		t.Fatal("refresh token must be stored only as a non-empty hash")
	}
	if store.createdToken.PlatformRole != "MEMBER" || store.createdToken.GsmRole != "STUDENT" {
		t.Fatalf("stored roles = %q/%q", store.createdToken.PlatformRole, store.createdToken.GsmRole)
	}
}

func TestCommitIdentityAndIssueSession_WhenIdentityIsRejected_DoesNotIssueTokens(t *testing.T) {
	t.Parallel()

	store := &fakeRefreshTokenStore{}
	identity := &fakeIdentityCoordinator{err: errors.New("identity rejected")}
	service := newUnitAuthService(store, time.Now().UTC())
	service.identity = identity

	pair, err := service.commitIdentityAndIssueSession(
		context.Background(),
		domain.UserIdentityCommand{UserID: 7},
		"user@example.com",
		"MEMBER",
		"STUDENT",
		"",
	)
	if err == nil || pair != nil {
		t.Fatalf("commitIdentityAndIssueSession() = %+v, %v; want failure", pair, err)
	}
	if identity.calls != 1 || store.createdToken != nil {
		t.Fatalf("identity calls=%d, created token=%+v", identity.calls, store.createdToken)
	}
}

func TestRefreshTokens_AccordingToSessionState(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.August, 26, 1, 2, 3, 0, time.UTC)
	t.Run("an active refresh session returns a replacement pair", func(t *testing.T) {
		t.Parallel()
		store := &fakeRefreshTokenStore{rotationSource: &domain.RefreshToken{
			UserID:       7,
			Email:        "user@example.com",
			GsmRole:      "STUDENT",
			PlatformRole: "ADMIN",
			ExpiresAt:    now.Add(time.Hour),
		}}

		pair, err := newUnitAuthService(store, now).RefreshTokens(context.Background(), "refresh-token")
		if err != nil || pair == nil || pair.AccessToken == "" || pair.RefreshToken == "" {
			t.Fatalf("RefreshTokens() = %+v, %v", pair, err)
		}
	})

	tests := []struct {
		name      string
		storeErr  error
		wantCause error
	}{
		{name: "an unknown refresh token is denied", storeErr: gorm.ErrRecordNotFound},
		{name: "an expired refresh token is denied", storeErr: domain.ErrRefreshTokenExpired},
		{name: "a storage failure is classified as unavailable", storeErr: errors.New("storage unavailable"), wantCause: ErrAuthenticationUnavailable},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			store := &fakeRefreshTokenStore{rotateErr: test.storeErr}
			pair, err := newUnitAuthService(store, now).RefreshTokens(context.Background(), "refresh-token")
			if err == nil || pair != nil {
				t.Fatalf("RefreshTokens() = %+v, %v; want denial", pair, err)
			}
			if test.wantCause != nil && !errors.Is(err, test.wantCause) {
				t.Fatalf("RefreshTokens() error = %v, want %v", err, test.wantCause)
			}
		})
	}
}

func TestLogout_AccordingToTokenOwnership(t *testing.T) {
	t.Parallel()

	t.Run("the owner can revoke a refresh token", func(t *testing.T) {
		t.Parallel()
		store := &fakeRefreshTokenStore{}
		service := newUnitAuthService(store, time.Now().UTC())

		if err := service.Logout(context.Background(), 7, "refresh-token"); err != nil {
			t.Fatalf("Logout() error = %v", err)
		}
		if store.revokeCalls != 1 || store.revokedUserID != 7 || store.revokedHash != HashToken("refresh-token") {
			t.Fatalf("revoke calls=%d user=%d hash=%q", store.revokeCalls, store.revokedUserID, store.revokedHash)
		}
	})

	tests := []struct {
		name      string
		storeErr  error
		wantCause error
	}{
		{name: "a missing token is denied", storeErr: gorm.ErrRecordNotFound},
		{name: "a token owned by another user is denied", storeErr: domain.ErrRefreshTokenOwnerMismatch},
		{name: "a storage failure is classified as unavailable", storeErr: errors.New("storage unavailable"), wantCause: ErrAuthenticationUnavailable},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			err := newUnitAuthService(&fakeRefreshTokenStore{revokeErr: test.storeErr}, time.Now().UTC()).Logout(
				context.Background(),
				7,
				"refresh-token",
			)
			if err == nil {
				t.Fatal("Logout() error = nil, want denial")
			}
			if test.wantCause != nil && !errors.Is(err, test.wantCause) {
				t.Fatalf("Logout() error = %v, want %v", err, test.wantCause)
			}
		})
	}
}

func TestPlatformRoleFromDataGSM_AccordingToProviderRole(t *testing.T) {
	t.Parallel()

	tests := []struct {
		providerRole string
		want         string
		wantError    bool
	}{
		{providerRole: "ADMIN", want: "ADMIN"},
		{providerRole: "USER", want: "MEMBER"},
		{providerRole: "", wantError: true},
		{providerRole: "SUPER_ADMIN", wantError: true},
	}
	for _, test := range tests {
		t.Run(test.providerRole, func(t *testing.T) {
			t.Parallel()
			got, err := PlatformRoleFromDataGSM(test.providerRole)
			if (err != nil) != test.wantError || got != test.want {
				t.Fatalf("PlatformRoleFromDataGSM(%q) = %q, %v", test.providerRole, got, err)
			}
		})
	}
}
