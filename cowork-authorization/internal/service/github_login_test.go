package service

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
)

const githubCallback = "https://app.example/auth/github/callback"

type githubFixture struct {
	svc          *AuthService
	store        *memoryLoginAttempts
	identity     *fakeIdentityCoordinator
	now          time.Time
	calls        int
	tokenForm    url.Values
	tokenBody    string
	tokenStatus  int
	userBody     string
	emailsBody   string
	emailsStatus int
	emailsHeader http.Header
}

func newGitHubFixture(t *testing.T) *githubFixture {
	t.Helper()
	f := &githubFixture{
		store:     &memoryLoginAttempts{attempts: map[string]LoginAttempt{}},
		identity:  &fakeIdentityCoordinator{userID: 7},
		now:       time.Date(2026, 11, 23, 0, 0, 0, 0, time.UTC),
		tokenBody: `{"access_token":"github-token","token_type":"bearer","scope":"user:email"}`, tokenStatus: 200,
		userBody:   `{"id":583231,"login":"octocat","name":null}`,
		emailsBody: `[{"email":"old@example.com","primary":false,"verified":true},{"email":"octo@example.com","primary":true,"verified":true}]`, emailsStatus: 200,
	}
	cfg := &config.AppConfig{
		JWTSecret: "unit-test-secret", JWTAccessExpire: 30 * time.Minute, JWTRefreshExpire: 24 * time.Hour,
		DataGSMClientID: "unit", DataGSMRedirectURIs: []string{"https://app.example/callback"},
		GitHubClientID: "github-client", GitHubClientSecret: "github-secret", GitHubRedirectURIs: []string{githubCallback},
	}
	client := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		f.calls++
		if r.URL.Host == "api.github.com" && r.Header.Get("User-Agent") != "cowork-authorization" {
			t.Error("GitHub REST API requires a User-Agent header")
		}
		switch r.URL.Host + r.URL.Path {
		case "github.com/login/oauth/access_token":
			if err := r.ParseForm(); err != nil {
				t.Fatal(err)
			}
			f.tokenForm = r.PostForm
			return jsonResponse(f.tokenStatus, f.tokenBody), nil
		case "api.github.com/user":
			if r.Header.Get("Authorization") != "Bearer github-token" {
				t.Error("missing bearer token")
			}
			return jsonResponse(200, f.userBody), nil
		case "api.github.com/user/emails":
			response := jsonResponse(f.emailsStatus, f.emailsBody)
			for name, values := range f.emailsHeader {
				response.Header[name] = values
			}
			return response, nil
		}
		t.Fatalf("unexpected GitHub request %s", r.URL)
		return nil, nil
	})}
	login, err := NewDataGSMLogin(cfg, f.store, client)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(login.Close)
	github, err := NewGitHubLogin(cfg, f.store, client)
	if err != nil {
		t.Fatal(err)
	}
	login.now, github.now = f.clock, f.clock
	f.svc = NewAuthService(cfg, f.identity, &fakeRefreshTokenStore{}, NewTokenService(cfg), login, github)
	f.svc.now = f.clock
	return f
}

func (f *githubFixture) clock() time.Time { return f.now }

func (f *githubFixture) callback(t *testing.T, raw string) url.Values {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	if u.Host != "github.com" || q.Get("code_challenge_method") != "S256" || q.Get("code_challenge") == "" ||
		q.Get("state") == "" || q.Get("scope") != "user:email" || q.Get("redirect_uri") != githubCallback || q.Has("code_verifier") {
		t.Fatalf("invalid authorization URL %s", raw)
	}
	return url.Values{"state": {q.Get("state")}, "code": {"github-code"}}
}

func (f *githubFixture) beginLogin(t *testing.T) (string, url.Values) {
	t.Helper()
	raw, session, err := f.svc.BeginLogin(context.Background(), ProviderGitHub, githubCallback)
	if err != nil {
		t.Fatal(err)
	}
	return session, f.callback(t, raw)
}

func (f *githubFixture) beginLink(t *testing.T, userID int64) (string, url.Values) {
	t.Helper()
	raw, session, err := f.svc.BeginLink(context.Background(), userID, ProviderGitHub, githubCallback)
	if err != nil {
		t.Fatal(err)
	}
	return session, f.callback(t, raw)
}

func TestGitHubLoginUsesTheNumericIDAndVerifiedPrimaryEmail(t *testing.T) {
	f := newGitHubFixture(t)
	session, callback := f.beginLogin(t)
	attempt := f.store.attempts[HashToken(session)]

	pair, err := f.svc.CompleteLogin(context.Background(), session, callback)
	if err != nil || pair == nil {
		t.Fatalf("pair=%v err=%v", pair, err)
	}
	if f.tokenForm.Get("code_verifier") != attempt.Authorization.CodeVerifier || f.tokenForm.Get("client_secret") != "github-secret" ||
		f.tokenForm.Get("redirect_uri") != githubCallback || f.tokenForm.Get("code") != "github-code" {
		t.Fatalf("token exchange did not use the saved attempt: %v", f.tokenForm)
	}
	command := f.identity.command
	if command.Provider != ProviderGitHub || command.ProviderSubject != "583231" || command.CommandType != domain.UserIdentityCommandUpsert ||
		command.GithubLogin == nil || *command.GithubLogin != "octocat" || command.Name != "octocat" ||
		command.GSMEmailVerified == nil || *command.GSMEmailVerified ||
		command.Email != "octo@example.com" || command.Student != nil || command.AccountID != nil {
		t.Fatalf("unexpected identity command %+v", command)
	}

	before := f.calls
	if _, err := f.svc.CompleteLogin(context.Background(), session, callback); !errors.Is(err, ErrAuthenticationRejected) || f.calls != before {
		t.Fatal("a reused callback reached GitHub")
	}
}

func TestGitHubLoginReportsAVerifiedSchoolEmailInAnyPosition(t *testing.T) {
	for name, tc := range map[string]struct {
		emails string
		want   bool
	}{
		"primary":            {`[{"email":"s24001@GSM.hs.kr","primary":true,"verified":true}]`, true},
		"secondary":          {`[{"email":"octo@example.com","primary":true,"verified":true},{"email":"s24001@gsm.hs.kr","primary":false,"verified":true}]`, true},
		"unverified":         {`[{"email":"octo@example.com","primary":true,"verified":true},{"email":"s24001@gsm.hs.kr","primary":false,"verified":false}]`, false},
		"subdomain":          {`[{"email":"octo@example.com","primary":true,"verified":true},{"email":"s24001@mail.gsm.hs.kr","primary":false,"verified":true}]`, false},
		"domain in username": {`[{"email":"gsm.hs.kr@example.com","primary":true,"verified":true}]`, false},
	} {
		t.Run(name, func(t *testing.T) {
			f := newGitHubFixture(t)
			f.emailsBody = tc.emails
			session, callback := f.beginLogin(t)
			if _, err := f.svc.CompleteLogin(context.Background(), session, callback); err != nil {
				t.Fatal(err)
			}
			if got := f.identity.command.GSMEmailVerified; got == nil || *got != tc.want {
				t.Fatalf("GSMEmailVerified=%v, want %v", got, tc.want)
			}
		})
	}
}

func TestGitHubLoginRejectsUnverifiedAccounts(t *testing.T) {
	for name, mutate := range map[string]func(*githubFixture){
		"no verified primary email": func(f *githubFixture) {
			f.emailsBody = `[{"email":"octo@example.com","primary":true,"verified":false},{"email":"x@example.com","primary":false,"verified":true}]`
		},
		"email permission missing": func(f *githubFixture) { f.emailsStatus = http.StatusNotFound },
		"invalid account ID":       func(f *githubFixture) { f.userBody = `{"id":0,"login":"octocat"}` },
		"invalid login":            func(f *githubFixture) { f.userBody = `{"id":1,"login":"-octo"}` },
		"expired code": func(f *githubFixture) {
			f.tokenBody = `{"error":"bad_verification_code","error_description":"sensitive"}`
		},
		"expired attempt": func(f *githubFixture) { f.now = f.now.Add(LoginAttemptTTL) },
	} {
		t.Run(name, func(t *testing.T) {
			f := newGitHubFixture(t)
			session, callback := f.beginLogin(t)
			mutate(f)
			if _, err := f.svc.CompleteLogin(context.Background(), session, callback); !errors.Is(err, ErrAuthenticationRejected) || f.identity.calls != 0 {
				t.Fatalf("err=%v identity calls=%d", err, f.identity.calls)
			}
		})
	}
}

func TestGitHubProviderFailuresAreNotAuthenticationFailures(t *testing.T) {
	f := newGitHubFixture(t)
	session, callback := f.beginLogin(t)
	f.tokenStatus = http.StatusTooManyRequests
	var limited *ProviderRateLimitError
	if _, err := f.svc.CompleteLogin(context.Background(), session, callback); !errors.As(err, &limited) {
		t.Fatalf("err=%v, want rate limit", err)
	}

	f = newGitHubFixture(t)
	session, callback = f.beginLogin(t)
	f.emailsStatus, f.emailsBody = http.StatusForbidden, `{"message":"API rate limit exceeded"}`
	f.emailsHeader = http.Header{"X-Ratelimit-Remaining": {"0"}, "X-Ratelimit-Reset": {strconv.FormatInt(time.Now().Add(time.Minute).Unix(), 10)}}
	limited = nil
	if _, err := f.svc.CompleteLogin(context.Background(), session, callback); !errors.As(err, &limited) || limited.RetryAfter == "" {
		t.Fatalf("err=%v, want rate limit with Retry-After", err)
	}

	f = newGitHubFixture(t)
	session, callback = f.beginLogin(t)
	f.tokenBody = `{"error":"incorrect_client_credentials"}`
	if _, err := f.svc.CompleteLogin(context.Background(), session, callback); !errors.Is(err, ErrAuthenticationUnavailable) {
		t.Fatalf("err=%v, want unavailable", err)
	}
}

func TestGitHubLoginStartRequiresAConfiguredProviderAndCallback(t *testing.T) {
	f := newGitHubFixture(t)
	if _, _, err := f.svc.BeginLogin(context.Background(), ProviderGitHub, "https://evil.example/callback"); !errors.Is(err, ErrInvalidLoginRequest) {
		t.Fatalf("unregistered callback err=%v", err)
	}
	if _, _, err := f.svc.BeginLogin(context.Background(), "GOOGLE", githubCallback); !errors.Is(err, ErrInvalidLoginRequest) {
		t.Fatalf("unknown provider err=%v", err)
	}
	if _, _, err := f.svc.BeginLink(context.Background(), 7, "DEV", "https://app.example/callback"); !errors.Is(err, ErrInvalidLoginRequest) {
		t.Fatalf("developer link err=%v", err)
	}
	f.svc.github = nil
	if _, _, err := f.svc.BeginLogin(context.Background(), ProviderGitHub, githubCallback); !errors.Is(err, ErrInvalidLoginRequest) {
		t.Fatalf("disabled provider err=%v", err)
	}
	if !f.svc.AllowsLoginOrigin("https://app.example") || f.svc.AllowsLoginOrigin("https://evil.example") {
		t.Fatal("origin policy changed")
	}
}

func TestGitHubLinkIsBoundToTheUserWhoStartedIt(t *testing.T) {
	f := newGitHubFixture(t)
	session, callback := f.beginLink(t, 7)
	if err := f.svc.CompleteLink(context.Background(), 8, session, callback); !errors.Is(err, ErrAuthenticationRejected) || f.calls != 0 {
		t.Fatalf("another user completed the link: err=%v calls=%d", err, f.calls)
	}

	session, callback = f.beginLink(t, 7)
	if _, err := f.svc.CompleteLogin(context.Background(), session, callback); !errors.Is(err, ErrAuthenticationRejected) || f.calls != 0 {
		t.Fatalf("a link attempt signed in: err=%v calls=%d", err, f.calls)
	}

	session, callback = f.beginLogin(t)
	if err := f.svc.CompleteLink(context.Background(), 7, session, callback); !errors.Is(err, ErrAuthenticationRejected) || f.calls != 0 {
		t.Fatalf("a login attempt linked an account: err=%v calls=%d", err, f.calls)
	}

	session, callback = f.beginLink(t, 7)
	if err := f.svc.CompleteLink(context.Background(), 7, session, callback); err != nil {
		t.Fatal(err)
	}
	command := f.identity.command
	if command.CommandType != domain.UserIdentityCommandLink || command.AccountID == nil || *command.AccountID != 7 ||
		command.Provider != ProviderGitHub || command.ProviderSubject != "583231" {
		t.Fatalf("unexpected link command %+v", command)
	}
}

func TestGitHubLinkFailsWhenTheOwnerCommitsAnotherAccount(t *testing.T) {
	f := newGitHubFixture(t)
	f.identity.userID = 8
	session, callback := f.beginLink(t, 7)
	if err := f.svc.CompleteLink(context.Background(), 7, session, callback); !errors.Is(err, ErrAuthenticationRejected) {
		t.Fatalf("err=%v", err)
	}

	f = newGitHubFixture(t)
	f.identity.err = ErrIdentityAlreadyLinked
	session, callback = f.beginLink(t, 7)
	if err := f.svc.CompleteLink(context.Background(), 7, session, callback); !errors.Is(err, ErrIdentityAlreadyLinked) {
		t.Fatalf("err=%v", err)
	}
}

func TestGitHubDisplayName(t *testing.T) {
	blank, long := "  ", strings.Repeat("가", 60)
	for _, tc := range []struct {
		name *string
		want string
	}{{nil, "octocat"}, {&blank, "octocat"}, {&long, strings.Repeat("가", 50)}} {
		if got := githubDisplayName(tc.name, "octocat"); got != tc.want {
			t.Fatalf("githubDisplayName()=%q, want %q", got, tc.want)
		}
	}
}
