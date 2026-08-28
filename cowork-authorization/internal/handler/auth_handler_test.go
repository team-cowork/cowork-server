package handler

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"github.com/cowork/authorization/internal/service"
	"github.com/gin-gonic/gin"
)

func TestMain(m *testing.M) {
	gin.SetMode(gin.TestMode)
	m.Run()
}

// fakeRefreshTokenStore is a hand-rolled stub of service.RefreshTokenStore,
// following the fake/stub pattern used by service/event_service_test.go and
// client/user_client_test.go: no real database, only in-memory call capture.
type fakeRefreshTokenStore struct {
	rotationSource *domain.RefreshToken
	rotateErr      error

	revokeErr       error
	revokedHash     string
	revokedUserID   int64
	revokeCallCount int

	createErr error
}

func (f *fakeRefreshTokenStore) CreateSession(
	_ context.Context,
	_ *domain.RefreshToken,
	_ time.Time,
	_ string,
) error {
	return f.createErr
}

func (f *fakeRefreshTokenStore) RotateSession(
	_ context.Context,
	_ string,
	_ string,
	_ time.Time,
	_ time.Time,
) (*domain.RefreshToken, error) {
	return f.rotationSource, f.rotateErr
}

func (f *fakeRefreshTokenStore) RevokeSession(
	_ context.Context,
	hash string,
	userID int64,
	_ time.Time,
	_ string,
) error {
	f.revokeCallCount++
	f.revokedHash = hash
	f.revokedUserID = userID
	return f.revokeErr
}

func newUnitAuthHandler(store service.RefreshTokenStore) *AuthHandler {
	cfg := &config.AppConfig{
		JWTSecret:              "unit-test-secret",
		JWTAccessExpire:        30 * time.Minute,
		JWTRefreshExpire:       24 * time.Hour,
		KafkaTopicUserPresence: "user.presence.event",
	}
	authSvc := service.NewAuthService(cfg, nil, store, service.NewTokenService(cfg))
	return NewAuthHandler(authSvc)
}

// performRequest drives handler through a real gin.Engine (rather than
// invoking it directly on a bare Context) so that deferred response-writer
// behavior — such as gin.Context.Status only flushing via WriteHeaderNow —
// behaves exactly as it does when served in production.
func performRequest(handler gin.HandlerFunc, method, path, body string, setup func(*gin.Context)) *httptest.ResponseRecorder {
	engine := gin.New()
	handlers := gin.HandlersChain{}
	if setup != nil {
		handlers = append(handlers, func(c *gin.Context) { setup(c) })
	}
	handlers = append(handlers, handler)
	engine.Handle(method, path, handlers...)

	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	engine.ServeHTTP(recorder, req)
	return recorder
}

func TestTokenRejectsRequestMissingRequiredFields(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name string
		body string
	}{
		{name: "empty body", body: `{}`},
		{name: "missing code_verifier", body: `{"code":"abc","redirect_uri":"https://app.example.com"}`},
		{name: "malformed json", body: `not-json`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			h := newUnitAuthHandler(&fakeRefreshTokenStore{})
			recorder := performRequest(h.Token, http.MethodPost, "/auth/token", test.body, nil)

			if recorder.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want %d", recorder.Code, http.StatusBadRequest)
			}
			assertErrorBody(t, recorder, "missing required fields")
		})
	}
}

func TestTokenReturnsServiceUnavailableWhenProviderIsUnreachable(t *testing.T) {
	t.Parallel()

	h := newUnitAuthHandler(&fakeRefreshTokenStore{})
	body := `{"code":"bad-code","code_verifier":"verifier","redirect_uri":"https://app.example.com/callback"}`
	recorder := performRequest(h.Token, http.MethodPost, "/auth/token", body, nil)

	// DataGSMTokenURL/ClientID are unset in the unit config, so the outbound
	// call fails to even reach a network endpoint. AuthService classifies
	// this transport-level failure as ErrAuthenticationUnavailable (503),
	// distinct from the provider explicitly rejecting the credentials (401).
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusServiceUnavailable)
	}
	assertErrorBody(t, recorder, "authentication temporarily unavailable")
	if cookie := findCookie(recorder, wsCookieName); cookie != nil {
		t.Fatalf("ws cookie must not be set on failed token exchange, got %+v", cookie)
	}
}

func TestRefreshRejectsRequestMissingRefreshToken(t *testing.T) {
	t.Parallel()

	h := newUnitAuthHandler(&fakeRefreshTokenStore{})
	recorder := performRequest(h.Refresh, http.MethodPost, "/auth/refresh", `{}`, nil)

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusBadRequest)
	}
	assertErrorBody(t, recorder, "refresh_token is required")
}

func TestRefreshReturnsUnauthorizedWhenRotationFails(t *testing.T) {
	t.Parallel()

	h := newUnitAuthHandler(&fakeRefreshTokenStore{rotateErr: domain.ErrRefreshTokenExpired})
	body := `{"refresh_token":"expired-token"}`
	recorder := performRequest(h.Refresh, http.MethodPost, "/auth/refresh", body, nil)

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusUnauthorized)
	}
	assertErrorBody(t, recorder, "invalid or expired refresh token")
}

func TestRefreshSetsWsCookieAndReturnsTokenPairOnSuccess(t *testing.T) {
	t.Parallel()

	store := &fakeRefreshTokenStore{rotationSource: &domain.RefreshToken{
		UserID:       7,
		Email:        "user@example.com",
		GsmRole:      "GENERAL_STUDENT",
		PlatformRole: "MEMBER",
		ExpiresAt:    time.Now().Add(time.Hour),
	}}
	h := newUnitAuthHandler(store)
	body := `{"refresh_token":"old-refresh-token"}`
	recorder := performRequest(h.Refresh, http.MethodPost, "/auth/refresh", body, nil)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}

	var pair service.TokenPair
	if err := json.Unmarshal(recorder.Body.Bytes(), &pair); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if pair.AccessToken == "" || pair.RefreshToken == "" {
		t.Fatalf("token pair = %+v, want populated tokens", pair)
	}

	cookie := findCookie(recorder, wsCookieName)
	if cookie == nil {
		t.Fatal("ws cookie was not set on successful refresh")
	}
	if cookie.Value != pair.AccessToken {
		t.Errorf("ws cookie value = %q, want it to match the issued access token", cookie.Value)
	}
	if cookie.Path != wsCookiePath {
		t.Errorf("ws cookie path = %q, want %q", cookie.Path, wsCookiePath)
	}
	if !cookie.HttpOnly || !cookie.Secure {
		t.Errorf("ws cookie httpOnly=%v secure=%v, want both true", cookie.HttpOnly, cookie.Secure)
	}
}

func TestLogoutRejectsRequestMissingRefreshToken(t *testing.T) {
	t.Parallel()

	h := newUnitAuthHandler(&fakeRefreshTokenStore{})
	recorder := performRequest(h.Logout, http.MethodPost, "/auth/signout", `{}`, func(c *gin.Context) {
		c.Set(userIDKey, int64(7))
	})

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusBadRequest)
	}
	assertErrorBody(t, recorder, "refresh_token is required")
}

func TestLogoutReturnsUnauthorizedWhenRevocationFails(t *testing.T) {
	t.Parallel()

	h := newUnitAuthHandler(&fakeRefreshTokenStore{revokeErr: domain.ErrRefreshTokenOwnerMismatch})
	recorder := performRequest(h.Logout, http.MethodPost, "/auth/signout", `{"refresh_token":"tok"}`, func(c *gin.Context) {
		c.Set(userIDKey, int64(7))
	})

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusUnauthorized)
	}
	assertErrorBody(t, recorder, "invalid refresh token")
}

func TestLogoutClearsWsCookieAndReturnsNoContentOnSuccess(t *testing.T) {
	t.Parallel()

	store := &fakeRefreshTokenStore{}
	h := newUnitAuthHandler(store)
	recorder := performRequest(h.Logout, http.MethodPost, "/auth/signout", `{"refresh_token":"tok"}`, func(c *gin.Context) {
		c.Set(userIDKey, int64(7))
	})

	if recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusNoContent)
	}
	if store.revokeCallCount != 1 || store.revokedUserID != 7 {
		t.Fatalf("revocation calls = %d for user %d, want 1 call for user 7", store.revokeCallCount, store.revokedUserID)
	}

	cookie := findCookie(recorder, wsCookieName)
	if cookie == nil {
		t.Fatal("ws cookie was not present in the clearing response")
	}
	if cookie.Value != "" || cookie.MaxAge >= 0 {
		t.Errorf("ws cookie = %+v, want an expired/empty clearing cookie", cookie)
	}
}

func TestLogoutUsesZeroUserIDWhenGatewayHeaderContextIsAbsent(t *testing.T) {
	t.Parallel()

	// RequireUserID always runs before Logout in the real router, but the
	// handler itself must not panic if userIDKey was never set.
	store := &fakeRefreshTokenStore{}
	h := newUnitAuthHandler(store)
	recorder := performRequest(h.Logout, http.MethodPost, "/auth/signout", `{"refresh_token":"tok"}`, nil)

	if recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusNoContent)
	}
	if store.revokedUserID != 0 {
		t.Fatalf("revokedUserID = %d, want 0 (gin.Context.GetInt64 zero value)", store.revokedUserID)
	}
}

func TestRequireUserIDRejectsMissingOrInvalidHeader(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name   string
		header string
	}{
		{name: "missing header", header: ""},
		{name: "non-numeric header", header: "not-a-number"},
		{name: "negative overflow-safe but non-numeric", header: "12abc"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			called := false
			engine := gin.New()
			engine.GET("/", RequireUserID(), func(*gin.Context) { called = true })

			req := httptest.NewRequest(http.MethodGet, "/", nil)
			if test.header != "" {
				req.Header.Set("X-User-Id", test.header)
			}
			recorder := httptest.NewRecorder()
			engine.ServeHTTP(recorder, req)

			if recorder.Code != http.StatusUnauthorized {
				t.Fatalf("status = %d, want %d", recorder.Code, http.StatusUnauthorized)
			}
			if called {
				t.Fatal("downstream handler must not run when X-User-Id is missing/invalid")
			}
		})
	}
}

func TestRequireUserIDSetsParsedUserIDAndContinuesChain(t *testing.T) {
	t.Parallel()

	var seenUserID int64
	engine := gin.New()
	engine.GET("/", RequireUserID(), func(c *gin.Context) {
		seenUserID = c.GetInt64(userIDKey)
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("X-User-Id", "12345")
	recorder := httptest.NewRecorder()
	engine.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d (handler chain should have continued)", recorder.Code, http.StatusOK)
	}
	if seenUserID != 12345 {
		t.Fatalf("downstream userID = %d, want 12345", seenUserID)
	}
}

func TestHealthReportsUp(t *testing.T) {
	t.Parallel()

	recorder := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(recorder)
	ctx.Request = httptest.NewRequest(http.MethodGet, "/health", nil)

	Health(ctx)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusOK)
	}
	var payload struct {
		Status    string    `json:"status"`
		Timestamp time.Time `json:"timestamp"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if payload.Status != "UP" {
		t.Errorf("status field = %q, want UP", payload.Status)
	}
	if payload.Timestamp.IsZero() {
		t.Error("timestamp field was not populated")
	}
}

func assertErrorBody(t *testing.T, recorder *httptest.ResponseRecorder, wantSubstring string) {
	t.Helper()
	var payload map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode error body: %v (body=%s)", err, recorder.Body.String())
	}
	if !strings.Contains(payload["error"], wantSubstring) {
		t.Errorf("error body = %q, want it to contain %q", payload["error"], wantSubstring)
	}
}

func findCookie(recorder *httptest.ResponseRecorder, name string) *http.Cookie {
	for _, cookie := range recorder.Result().Cookies() {
		if cookie.Name == name {
			return cookie
		}
	}
	return nil
}
