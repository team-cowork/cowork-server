package service

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"github.com/golang-jwt/jwt/v5"
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
	createdToken    *domain.RefreshToken
	createdAt       time.Time
	createdTopic    string
	createErr       error
	rotationSource  *domain.RefreshToken
	rotateErr       error
	rotateOldHash   string
	rotateNewHash   string
	rotateExpiresAt time.Time
	rotateAt        time.Time
	rotateCallCount int
	revokedHash     string
	revokedUserID   int64
	revokedAt       time.Time
	revokedTopic    string
	revokeErr       error
	revokeCallCount int
}

type fakeIdentityCoordinator struct {
	userID int64
	err    error
	calls  int
}

func (f *fakeIdentityCoordinator) EnsureUser(
	_ context.Context,
	_ domain.UserIdentityCommand,
) (int64, error) {
	f.calls++
	return f.userID, f.err
}

func (f *fakeRefreshTokenStore) CreateSession(
	_ context.Context,
	token *domain.RefreshToken,
	occurredAt time.Time,
	topic string,
) error {
	f.createdToken = token
	f.createdAt = occurredAt
	f.createdTopic = topic
	return f.createErr
}

func (f *fakeRefreshTokenStore) RotateSession(
	_ context.Context,
	oldHash string,
	newHash string,
	newExpiresAt time.Time,
	now time.Time,
) (*domain.RefreshToken, error) {
	f.rotateCallCount++
	f.rotateOldHash = oldHash
	f.rotateNewHash = newHash
	f.rotateExpiresAt = newExpiresAt
	f.rotateAt = now
	return f.rotationSource, f.rotateErr
}

func (f *fakeRefreshTokenStore) RevokeSession(
	_ context.Context,
	hash string,
	userID int64,
	occurredAt time.Time,
	topic string,
) error {
	f.revokeCallCount++
	f.revokedHash = hash
	f.revokedUserID = userID
	f.revokedAt = occurredAt
	f.revokedTopic = topic
	return f.revokeErr
}

func newUnitAuthService(store RefreshTokenStore, now time.Time) *AuthService {
	cfg := &config.AppConfig{
		JWTSecret:              "unit-test-secret",
		JWTAccessExpire:        30 * time.Minute,
		JWTRefreshExpire:       24 * time.Hour,
		KafkaTopicUserPresence: "user.presence.event",
	}
	service := NewAuthService(cfg, nil, store, NewTokenService(cfg))
	service.now = func() time.Time { return now }
	return service
}

func TestIssueNewSessionDelegatesTokenPresenceAndOutboxToOneStoreOperation(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.August, 26, 1, 2, 3, 456000000, time.UTC)
	store := &fakeRefreshTokenStore{}
	service := newUnitAuthService(store, now)

	pair, err := service.issueNewSession(context.Background(), 7, "user@example.com", "MEMBER", "STUDENT", "device")
	if err != nil {
		t.Fatalf("issueNewSession() error = %v", err)
	}
	if pair.RefreshToken == "" || pair.AccessToken == "" {
		t.Fatal("issued token pair is empty")
	}
	if store.createdToken == nil || store.createdToken.UserID != 7 {
		t.Fatalf("created token = %+v", store.createdToken)
	}
	if store.createdToken.TokenHash == "" || store.createdToken.TokenHash == pair.RefreshToken {
		t.Fatal("refresh token must be stored only as a non-empty hash")
	}
	if store.createdToken.PlatformRole != "MEMBER" {
		t.Fatalf("stored platform role = %q, want MEMBER", store.createdToken.PlatformRole)
	}
	if !store.createdAt.Equal(now) || store.createdTopic != "user.presence.event" {
		t.Fatalf("presence mutation = %s/%q, want %s/user.presence.event", store.createdAt, store.createdTopic, now)
	}
}

func TestIdentityOwnerCommitMustSucceedBeforeSessionAndTokenIssue(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.August, 27, 1, 2, 3, 0, time.UTC)
	store := &fakeRefreshTokenStore{}
	identity := &fakeIdentityCoordinator{err: errors.New("owner rejected command")}
	service := newUnitAuthService(store, now)
	service.identity = identity

	command := domain.UserIdentityCommand{UserID: 7}
	pair, err := service.commitIdentityAndIssueSession(
		context.Background(),
		command,
		"user@example.com",
		"MEMBER",
		"STUDENT",
		"",
	)
	if err == nil || pair != nil {
		t.Fatalf("commitIdentityAndIssueSession() = %+v, %v; want failure", pair, err)
	}
	if identity.calls != 1 || store.createdToken != nil {
		t.Fatalf("owner calls=%d createdToken=%+v; token/session must follow SUCCEEDED", identity.calls, store.createdToken)
	}

	identity.err = nil
	identity.userID = 7
	pair, err = service.commitIdentityAndIssueSession(
		context.Background(),
		command,
		"user@example.com",
		"MEMBER",
		"STUDENT",
		"",
	)
	if err != nil || pair == nil || store.createdToken == nil {
		t.Fatalf("successful owner commit did not issue session: pair=%+v token=%+v err=%v", pair, store.createdToken, err)
	}
}

func TestRefreshUsesSingleAtomicRotationOperation(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.August, 26, 1, 2, 3, 0, time.UTC)
	store := &fakeRefreshTokenStore{rotationSource: &domain.RefreshToken{
		UserID:       7,
		Email:        "user@example.com",
		GsmRole:      "STUDENT",
		PlatformRole: "ADMIN",
		ExpiresAt:    now.Add(time.Hour),
	}}
	service := newUnitAuthService(store, now)

	pair, err := service.RefreshTokens(context.Background(), "old-refresh-token")
	if err != nil {
		t.Fatalf("RefreshTokens() error = %v", err)
	}
	if pair.RefreshToken == "" || store.rotateCallCount != 1 {
		t.Fatalf("atomic rotations = %d, pair = %+v", store.rotateCallCount, pair)
	}
	if store.rotateOldHash != HashToken("old-refresh-token") {
		t.Fatal("old refresh token hash was not passed to the atomic rotation")
	}
	if store.rotateNewHash == "" || !store.rotateAt.Equal(now) {
		t.Fatalf("rotation arguments = hash %q at %s", store.rotateNewHash, store.rotateAt)
	}
	if !store.rotateExpiresAt.Equal(now.Add(24 * time.Hour)) {
		t.Fatalf("replacement expiry = %s", store.rotateExpiresAt)
	}
	claims := &Claims{}
	parser := jwt.NewParser(jwt.WithoutClaimsValidation())
	if _, err := parser.ParseWithClaims(pair.AccessToken, claims, func(_ *jwt.Token) (any, error) {
		return []byte(service.cfg.JWTSecret), nil
	}); err != nil {
		t.Fatalf("parse refreshed access token: %v", err)
	}
	if claims.Role != "ADMIN" {
		t.Fatalf("refreshed access role = %q, want ADMIN", claims.Role)
	}
}

func TestPlatformRoleFromDataGSMMapsProviderContractAndRejectsUnknownValues(t *testing.T) {
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
			if (err != nil) != test.wantError {
				t.Fatalf("PlatformRoleFromDataGSM(%q) error = %v", test.providerRole, err)
			}
			if got != test.want {
				t.Fatalf("PlatformRoleFromDataGSM(%q) = %q, want %q", test.providerRole, got, test.want)
			}
		})
	}
}

func TestProviderStatusErrorCannotExposeResponseBody(t *testing.T) {
	t.Parallel()

	err := providerStatusError("token", 401)
	if got, want := err.Error(), "token endpoint returned status 401"; got != want {
		t.Fatalf("providerStatusError() = %q, want body-free %q", got, want)
	}
}

func TestAuthenticationInfrastructureFailuresAreClassifiedAsUnavailable(t *testing.T) {
	t.Parallel()

	if err := providerStatusError("token", 503); !errors.Is(err, ErrAuthenticationUnavailable) {
		t.Fatalf("provider 503 error = %v, want ErrAuthenticationUnavailable", err)
	}

	now := time.Date(2026, time.August, 27, 1, 2, 3, 0, time.UTC)
	store := &fakeRefreshTokenStore{createErr: errors.New("database unavailable")}
	identity := &fakeIdentityCoordinator{userID: 7}
	service := newUnitAuthService(store, now)
	service.identity = identity

	pair, err := service.commitIdentityAndIssueSession(
		context.Background(),
		domain.UserIdentityCommand{UserID: 7},
		"user@example.com",
		"MEMBER",
		"STUDENT",
		"",
	)
	if pair != nil || !errors.Is(err, ErrAuthenticationUnavailable) {
		t.Fatalf("session persistence failure = pair %+v, err %v; want unavailable", pair, err)
	}

	store.rotateErr = errors.New("database unavailable")
	if _, err := service.RefreshTokens(context.Background(), "refresh-token"); !errors.Is(err, ErrAuthenticationUnavailable) {
		t.Fatalf("refresh persistence failure = %v, want unavailable", err)
	}

	store.revokeErr = errors.New("database unavailable")
	if err := service.Logout(context.Background(), 7, "refresh-token"); !errors.Is(err, ErrAuthenticationUnavailable) {
		t.Fatalf("logout persistence failure = %v, want unavailable", err)
	}
}

func TestRefreshLoserCannotReturnMintedSuccessor(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.August, 26, 1, 2, 3, 0, time.UTC)
	store := &fakeRefreshTokenStore{rotateErr: gorm.ErrRecordNotFound}
	service := newUnitAuthService(store, now)

	pair, err := service.RefreshTokens(context.Background(), "already-rotated-or-revoked")
	if err == nil || pair != nil {
		t.Fatalf("RefreshTokens() = %+v, %v; want no successor", pair, err)
	}
	if store.rotateCallCount != 1 {
		t.Fatalf("atomic rotations = %d, want 1", store.rotateCallCount)
	}
}

func TestLogoutDelegatesOwnershipRevocationAndPresenceToOneStoreOperation(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.August, 26, 1, 2, 3, 0, time.UTC)
	store := &fakeRefreshTokenStore{}
	service := newUnitAuthService(store, now)

	if err := service.Logout(context.Background(), 7, "refresh-token"); err != nil {
		t.Fatalf("Logout() error = %v", err)
	}
	if store.revokedHash != HashToken("refresh-token") || store.revokedUserID != 7 {
		t.Fatalf("revocation = hash %q user %d", store.revokedHash, store.revokedUserID)
	}
	if !store.revokedAt.Equal(now) || store.revokedTopic != "user.presence.event" {
		t.Fatalf("presence mutation = %s/%q", store.revokedAt, store.revokedTopic)
	}
}

func TestLogoutDoesNotSplitRevocationFromPresenceFailure(t *testing.T) {
	t.Parallel()

	store := &fakeRefreshTokenStore{revokeErr: errors.New("transaction rolled back")}
	service := newUnitAuthService(store, time.Now().UTC())

	err := service.Logout(context.Background(), 7, "refresh-token")
	if err == nil {
		t.Fatal("Logout() expected transaction failure")
	}
	if store.revokeCallCount != 1 {
		t.Fatalf("atomic revoke calls = %d, want 1", store.revokeCallCount)
	}
}

// newExchangeUnitAuthService wires an AuthService whose DataGSM HTTP calls are
// stubbed via RoundTripper (following client/user_client_test.go's pattern)
// and whose identity commit goes through a fakeIdentityCoordinator, so
// ExchangeCode is exercised end-to-end without any real network, Kafka, or
// database dependency.
func newExchangeUnitAuthService(
	dataGSM roundTripFunc,
	identity *fakeIdentityCoordinator,
	store RefreshTokenStore,
) *AuthService {
	cfg := &config.AppConfig{
		JWTSecret:              "unit-test-secret",
		JWTAccessExpire:        30 * time.Minute,
		JWTRefreshExpire:       24 * time.Hour,
		KafkaTopicUserPresence: "user.presence.event",
		DataGSMClientID:        "unit-test-client",
		DataGSMTokenURL:        "https://datagsm.invalid/oauth/token",
		DataGSMUserInfoURL:     "https://datagsm.invalid/oauth/userinfo",
	}

	svc := NewAuthService(cfg, identity, store, NewTokenService(cfg))
	svc.httpClient = &http.Client{Transport: dataGSM}
	return svc
}

func dataGSMUserInfoBody(t *testing.T, info DataGSMUserInfo) string {
	t.Helper()
	body, err := json.Marshal(info)
	if err != nil {
		t.Fatal(err)
	}
	return string(body)
}

func TestExchangeCodePropagatesNonStudentRejectionBeforeCommittingIdentity(t *testing.T) {
	t.Parallel()

	identity := &fakeIdentityCoordinator{userID: 1}
	info := DataGSMUserInfo{ID: 99, Email: "staff@example.com", Role: "USER", IsStudent: false}
	svc := newExchangeUnitAuthService(func(req *http.Request) (*http.Response, error) {
		if strings.Contains(req.URL.String(), "/oauth/token") {
			return jsonResponse(http.StatusOK, `{"access_token":"provider-access-token"}`), nil
		}
		return jsonResponse(http.StatusOK, dataGSMUserInfoBody(t, info)), nil
	}, identity, &fakeRefreshTokenStore{})

	pair, err := svc.ExchangeCode(context.Background(), "auth-code", "verifier", "https://app.example.com/callback")
	if err == nil || pair != nil {
		t.Fatalf("ExchangeCode() = %+v, %v; want rejection for non-student accounts", pair, err)
	}
	if identity.calls != 0 {
		t.Fatalf("identity commit calls = %d, want 0 for a non-student account", identity.calls)
	}
}

func TestExchangeCodeIssuesSessionAfterCommittingStudentIdentity(t *testing.T) {
	t.Parallel()

	info := DataGSMUserInfo{
		ID:        99,
		Email:     "student@example.com",
		Role:      "USER",
		IsStudent: true,
		Student: &DataGSMStudent{
			ID:       501,
			Name:     "Student",
			Sex:      "MAN",
			Grade:    2,
			ClassNum: 3,
			Number:   14,
			Major:    "SW_DEVELOPMENT",
			Role:     "GENERAL_STUDENT",
		},
	}
	store := &fakeRefreshTokenStore{}
	identity := &fakeIdentityCoordinator{userID: 99}
	svc := newExchangeUnitAuthService(func(req *http.Request) (*http.Response, error) {
		if strings.Contains(req.URL.String(), "/oauth/token") {
			return jsonResponse(http.StatusOK, `{"access_token":"provider-access-token"}`), nil
		}
		return jsonResponse(http.StatusOK, dataGSMUserInfoBody(t, info)), nil
	}, identity, store)

	pair, err := svc.ExchangeCode(context.Background(), "auth-code", "verifier", "https://app.example.com/callback")
	if err != nil {
		t.Fatalf("ExchangeCode() error = %v", err)
	}
	if pair.AccessToken == "" || pair.RefreshToken == "" {
		t.Fatalf("issued pair = %+v, want populated tokens", pair)
	}
	if identity.calls != 1 {
		t.Fatalf("identity commit calls = %d, want 1", identity.calls)
	}
	if store.createdToken == nil || store.createdToken.UserID != 99 {
		t.Fatalf("session stored for user %+v, want the id returned by EnsureUser (99)", store.createdToken)
	}
	if store.createdToken.PlatformRole != "MEMBER" {
		t.Fatalf("stored platform role = %q, want MEMBER (mapped from provider role USER)", store.createdToken.PlatformRole)
	}
}

func TestExchangeCodeRejectsUnsupportedProviderRoleBeforeCommittingIdentity(t *testing.T) {
	t.Parallel()

	identity := &fakeIdentityCoordinator{userID: 1}
	info := DataGSMUserInfo{
		ID:        99,
		Email:     "student@example.com",
		Role:      "SUPER_ADMIN",
		IsStudent: true,
		Student:   &DataGSMStudent{ID: 501, Name: "Student", Role: "GENERAL_STUDENT"},
	}
	svc := newExchangeUnitAuthService(func(req *http.Request) (*http.Response, error) {
		if strings.Contains(req.URL.String(), "/oauth/token") {
			return jsonResponse(http.StatusOK, `{"access_token":"provider-access-token"}`), nil
		}
		return jsonResponse(http.StatusOK, dataGSMUserInfoBody(t, info)), nil
	}, identity, &fakeRefreshTokenStore{})

	if _, err := svc.ExchangeCode(context.Background(), "auth-code", "verifier", "https://app.example.com/callback"); err == nil {
		t.Fatal("ExchangeCode() expected error for an unsupported provider role")
	}
	if identity.calls != 0 {
		t.Fatalf("identity commit calls = %d, want 0 when the provider role cannot be mapped", identity.calls)
	}
}

func TestExchangeCodeWrapsTokenEndpointFailure(t *testing.T) {
	t.Parallel()

	svc := newExchangeUnitAuthService(func(*http.Request) (*http.Response, error) {
		return jsonResponse(http.StatusUnauthorized, `{"error":"invalid_grant"}`), nil
	}, &fakeIdentityCoordinator{}, &fakeRefreshTokenStore{})

	_, err := svc.ExchangeCode(context.Background(), "bad-code", "verifier", "https://app.example.com/callback")
	if err == nil {
		t.Fatal("ExchangeCode() expected error when the token endpoint rejects the code")
	}
	if !strings.Contains(err.Error(), "failed to exchange code") {
		t.Fatalf("ExchangeCode() error = %q, want it wrapped with %%w context: %q", err.Error(), "failed to exchange code")
	}
}

func TestExchangeCodeWrapsUserInfoEndpointFailure(t *testing.T) {
	t.Parallel()

	svc := newExchangeUnitAuthService(func(req *http.Request) (*http.Response, error) {
		if strings.Contains(req.URL.String(), "/oauth/token") {
			return jsonResponse(http.StatusOK, `{"access_token":"provider-access-token"}`), nil
		}
		return jsonResponse(http.StatusServiceUnavailable, `{}`), nil
	}, &fakeIdentityCoordinator{}, &fakeRefreshTokenStore{})

	_, err := svc.ExchangeCode(context.Background(), "auth-code", "verifier", "https://app.example.com/callback")
	if err == nil {
		t.Fatal("ExchangeCode() expected error when the userinfo endpoint fails")
	}
	if !strings.Contains(err.Error(), "failed to fetch user info") {
		t.Fatalf("ExchangeCode() error = %q, want it wrapped with %%w context: %q", err.Error(), "failed to fetch user info")
	}
}

func TestExchangeCodeRejectsEmptyAccessTokenInTokenResponse(t *testing.T) {
	t.Parallel()

	svc := newExchangeUnitAuthService(func(*http.Request) (*http.Response, error) {
		return jsonResponse(http.StatusOK, `{"access_token":""}`), nil
	}, &fakeIdentityCoordinator{}, &fakeRefreshTokenStore{})

	if _, err := svc.ExchangeCode(context.Background(), "auth-code", "verifier", "https://app.example.com/callback"); err == nil {
		t.Fatal("ExchangeCode() expected error for an empty access_token")
	}
}
