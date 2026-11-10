package service

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"github.com/golang-jwt/jwt/v5"
	datagsm "github.com/tmp-it-play/datagsm-sdk-go"
	"github.com/tmp-it-play/datagsm-sdk-go/oauth"
)

type memoryLoginAttempts struct {
	attempts map[string]LoginAttempt
	err      error
}

func (m *memoryLoginAttempts) Save(_ context.Context, key string, a LoginAttempt, _ time.Duration) error {
	if m.err != nil {
		return m.err
	}
	m.attempts[key] = a
	return nil
}
func (m *memoryLoginAttempts) Take(_ context.Context, key string) (LoginAttempt, error) {
	if m.err != nil {
		return LoginAttempt{}, m.err
	}
	a, ok := m.attempts[key]
	delete(m.attempts, key)
	if !ok {
		return a, ErrLoginAttemptNotFound
	}
	return a, nil
}

type loginFixture struct {
	login                                 *DataGSMLogin
	store                                 *memoryLoginAttempts
	key                                   *rsa.PrivateKey
	now                                   time.Time
	nonce, subject, userSubject, audience string
	expired                               bool
	calls, jwksCalls                      int
	tokenStatus                           int
	form                                  url.Values
}

func newLoginFixture(t *testing.T) *loginFixture {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	f := &loginFixture{key: key, now: time.Date(2026, 11, 10, 0, 0, 0, 0, time.UTC), subject: "student@gsm.hs.kr", userSubject: "student@gsm.hs.kr", audience: "unit", tokenStatus: 200, store: &memoryLoginAttempts{attempts: map[string]LoginAttempt{}}}
	f.login, err = NewDataGSMLogin(&config.AppConfig{DataGSMClientID: "unit", DataGSMRedirectURIs: []string{"https://app.example/callback", "cowork://oauth/callback"}}, f.store, &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		f.calls++
		switch r.URL.Path {
		case "/v1/oauth/token":
			if r.Header.Get("Content-Type") != "application/x-www-form-urlencoded" {
				t.Error("SDK must send form encoding")
			}
			if err := r.ParseForm(); err != nil {
				t.Fatal(err)
			}
			f.form = r.Form
			if f.tokenStatus != 200 {
				return jsonResponse(f.tokenStatus, `{"error":"invalid_grant","error_description":"sensitive provider text"}`), nil
			}
			expires := f.now.Add(time.Hour).Unix()
			if f.expired {
				expires = f.now.Add(-time.Second).Unix()
			}
			claims := jwt.MapClaims{"iss": oauth.DefaultAuthorizationURL, "sub": f.subject, "aud": []string{f.audience}, "nonce": f.nonce, "iat": f.now.Add(-time.Minute).Unix(), "exp": expires}
			token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
			token.Header["kid"] = "unit-key"
			signed, err := token.SignedString(f.key)
			if err != nil {
				t.Fatal(err)
			}
			body, _ := json.Marshal(oauth.Token{AccessToken: "provider-token", TokenType: "Bearer", ExpiresIn: 3600, Scope: "openid datagsm:account_read datagsm:student_read", IDToken: &signed})
			return jsonResponse(200, string(body)), nil
		case "/v1/oauth/jwks":
			f.jwksCalls++
			keys := oauth.JWKS{Keys: []oauth.JWK{{KeyID: "unit-key", KeyType: "RSA", N: base64.RawURLEncoding.EncodeToString(f.key.N.Bytes()), E: base64.RawURLEncoding.EncodeToString(big.NewInt(int64(f.key.E)).Bytes())}}}
			body, _ := json.Marshal(keys)
			return jsonResponse(200, string(body)), nil
		case "/userinfo":
			if r.Header.Get("Authorization") != "Bearer provider-token" {
				t.Error("missing bearer token")
			}
			info := eligibleUser()
			info.Subject = f.userSubject
			body, _ := json.Marshal(info)
			return jsonResponse(200, string(body)), nil
		default:
			t.Fatalf("unexpected provider path %s", r.URL.Path)
			return nil, nil
		}
	})})
	if err != nil {
		t.Fatal(err)
	}
	f.login.now = func() time.Time { return f.now }
	t.Cleanup(f.login.Close)
	return f
}
func (f *loginFixture) begin(t *testing.T) (string, url.Values) {
	t.Helper()
	raw, session, err := f.login.Begin(context.Background(), "https://app.example/callback")
	if err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	f.nonce = q.Get("nonce")
	if q.Get("code_challenge_method") != "S256" || f.nonce == "" || q.Get("state") == "" || strings.Contains(raw, "code_verifier") {
		t.Fatal("invalid authorization URL")
	}
	if q.Get("scope") != "openid datagsm:account_read datagsm:student_read" {
		t.Fatal("missing explicit scopes")
	}
	return session, url.Values{"state": {q.Get("state")}, "code": {"provider-code"}}
}
func eligibleUser() *oauth.UserInfo {
	return &oauth.UserInfo{ID: 99, Subject: "student@gsm.hs.kr", Email: "student@gsm.hs.kr", Role: "USER", Status: "ACTIVE", ObjectType: datagsm.Ptr("STUDENT"), Student: &datagsm.Student{ID: 501, Name: "Student", Sex: datagsm.SexMan, Role: datagsm.StudentRole("GRADUATE")}}
}

func TestServerManagedLogin(t *testing.T) {
	f := newLoginFixture(t)
	session, callback := f.begin(t)
	attempt := f.store.attempts[HashToken(session)]
	user, err := f.login.Complete(context.Background(), session, callback)
	if err != nil || user.ID != 99 {
		t.Fatalf("user=%+v err=%v", user, err)
	}
	if f.form.Get("code_verifier") != attempt.Authorization.CodeVerifier || f.form.Get("redirect_uri") != attempt.RedirectURI {
		t.Fatal("exchange did not use saved attempt")
	}
	before := f.calls
	if _, err = f.login.Complete(context.Background(), session, callback); !errors.Is(err, ErrAuthenticationRejected) || f.calls != before {
		t.Fatal("replayed attempt reached the provider")
	}
	// The same signing key is reused across independent, single-use login attempts.
	session, callback = f.begin(t)
	if _, err = f.login.Complete(context.Background(), session, callback); err != nil {
		t.Fatal(err)
	}
	if f.jwksCalls != 1 {
		t.Fatalf("JWKS calls=%d", f.jwksCalls)
	}
}
func TestLoginAttemptBoundaries(t *testing.T) {
	for _, name := range []string{"different client", "state mismatch", "expired attempt", "provider denial", "duplicate code"} {
		t.Run(name, func(t *testing.T) {
			f := newLoginFixture(t)
			session, callback := f.begin(t)
			switch name {
			case "different client":
				session = oauth.NewCodeVerifier()
			case "state mismatch":
				callback.Set("state", "wrong")
			case "expired attempt":
				f.now = f.now.Add(LoginAttemptTTL)
			case "provider denial":
				callback.Del("code")
				callback.Set("error", "access_denied")
			case "duplicate code":
				callback.Add("code", "another")
			}
			if _, err := f.login.Complete(context.Background(), session, callback); !errors.Is(err, ErrAuthenticationRejected) || f.calls != 0 {
				t.Fatalf("err=%v provider calls=%d", err, f.calls)
			}
		})
	}
}
func TestOIDCIdentityVerification(t *testing.T) {
	for _, name := range []string{"wrong nonce", "wrong audience", "expired ID token", "different UserInfo subject"} {
		t.Run(name, func(t *testing.T) {
			f := newLoginFixture(t)
			session, callback := f.begin(t)
			switch name {
			case "wrong nonce":
				f.nonce = "wrong"
			case "wrong audience":
				f.audience = "another-client"
			case "expired ID token":
				f.expired = true
			case "different UserInfo subject":
				f.userSubject = "other@gsm.hs.kr"
			}
			if _, err := f.login.Complete(context.Background(), session, callback); !errors.Is(err, ErrAuthenticationRejected) {
				t.Fatalf("accepted invalid OIDC identity: %v", err)
			}
		})
	}
}

// TODO(legacy-login): Remove this compatibility test with Exchange after the web and mobile implementations migrate to server-managed OAuth/OIDC.
func TestLegacyPKCEExchangeUsesSDK(t *testing.T) {
	f := newLoginFixture(t)
	verifier := oauth.NewCodeVerifier()
	user, err := f.login.Exchange(context.Background(), "code", verifier, "cowork://oauth/callback")
	if err != nil || user.ID != 99 || f.form.Get("code_verifier") != verifier || f.jwksCalls != 0 {
		t.Fatalf("legacy exchange: %v", err)
	}
	before := f.calls
	for _, request := range []struct{ verifier, redirect string }{{"short", "cowork://oauth/callback"}, {verifier, "https://evil.example/callback"}} {
		if _, err = f.login.Exchange(context.Background(), "code", request.verifier, request.redirect); !errors.Is(err, ErrInvalidLoginRequest) {
			t.Fatal(err)
		}
	}
	if f.calls != before {
		t.Fatal("invalid client input reached the provider")
	}
}
func TestLoginStorageAndOriginPolicy(t *testing.T) {
	f := newLoginFixture(t)
	for _, origin := range []string{"", "https://app.example"} {
		if !f.login.AllowsOrigin(origin) {
			t.Fatal(origin)
		}
	}
	for _, origin := range []string{"null", "https://app.example.evil.test", "https://evil.example", "cowork://oauth"} {
		if f.login.AllowsOrigin(origin) {
			t.Fatal(origin)
		}
	}
	if _, _, err := f.login.Begin(context.Background(), "https://evil.example/callback"); !errors.Is(err, ErrInvalidLoginRequest) {
		t.Fatal(err)
	}
	f.store.err = errors.New("Redis unavailable")
	if _, _, err := f.login.Begin(context.Background(), "https://app.example/callback"); !errors.Is(err, ErrAuthenticationUnavailable) {
		t.Fatal(err)
	}
}
func TestProviderErrorClassification(t *testing.T) {
	for _, tc := range []struct{ err, want error }{
		{&datagsm.APIError{StatusCode: 400, Code: "invalid_grant", Message: "private"}, ErrAuthenticationRejected},
		{&datagsm.APIError{StatusCode: 401, Code: "invalid_client", Message: "private"}, ErrAuthenticationUnavailable},
		{&datagsm.APIError{StatusCode: 403, Message: "private"}, ErrAuthenticationUnavailable},
		{&datagsm.APIError{StatusCode: 500, Message: "private"}, ErrAuthenticationUnavailable},
		{errors.New("network private"), ErrAuthenticationUnavailable},
	} {
		if got := providerError(tc.err); !errors.Is(got, tc.want) || strings.Contains(got.Error(), "private") {
			t.Fatal(got)
		}
	}
	var limited *ProviderRateLimitError
	if !errors.As(providerError(&datagsm.APIError{StatusCode: 429, RetryAfter: "60"}), &limited) || limited.RetryAfter != "60" {
		t.Fatal("lost rate limit")
	}
}
func TestStudentLoginAllowsNullableProfile(t *testing.T) {
	cfg := &config.AppConfig{JWTSecret: "unit-secret", JWTAccessExpire: time.Minute, JWTRefreshExpire: time.Hour}
	for _, tc := range []struct {
		name   string
		mutate func(*oauth.UserInfo)
		valid  bool
	}{
		{"nullable major and school numbers", func(*oauth.UserInfo) {}, true},
		{"unknown student role is retained", func(u *oauth.UserInfo) { u.Student.Role = "NEW_ROLE" }, true},
		{"teacher", func(u *oauth.UserInfo) { u.ObjectType = datagsm.Ptr("TEACHER") }, false},
		{"missing student scope", func(u *oauth.UserInfo) { u.Student = nil }, false},
		{"unknown platform role", func(u *oauth.UserInfo) { u.Role = "SUPER_ADMIN" }, false},
		{"invalid school number", func(u *oauth.UserInfo) { u.Student.Grade = datagsm.Ptr(256) }, false},
		{"invalid identity", func(u *oauth.UserInfo) { u.ID = 0 }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			info := eligibleUser()
			tc.mutate(info)
			identity := &fakeIdentityCoordinator{userID: 99}
			store := &fakeRefreshTokenStore{}
			svc := NewAuthService(cfg, identity, store, NewTokenService(cfg), nil)
			pair, err := svc.issueUserSession(context.Background(), info)
			if tc.valid {
				if err != nil || pair == nil || identity.command.Major != nil || identity.command.Grade != nil || identity.command.SchemaVersion != domain.UserIdentityCommandSchemaVersion || store.createdToken.PlatformRole != "MEMBER" {
					t.Fatalf("pair=%+v err=%v command=%+v", pair, err, identity.command)
				}
			} else if err == nil || identity.calls != 0 || store.createdToken != nil {
				t.Fatal("ineligible user received a session")
			}
		})
	}
}
