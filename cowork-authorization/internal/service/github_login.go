package service

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/go-github/v93/github"
	"github.com/tmp-it-play/datagsm-sdk-go/oauth"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/endpoints"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
)

const githubScope = "user:email"

// GitHubLogin runs the GitHub OAuth web flow. GitHub OAuth is not OpenID Connect, so state and PKCE
// protect the request, and the access token reads the account from the REST API.
type GitHubLogin struct {
	clientID     string
	clientSecret string
	redirects    map[string]bool
	attempts     LoginAttemptStore
	http         *http.Client
	now          func() time.Time
}

// NewGitHubLogin returns nil when GitHub login is not configured.
func NewGitHubLogin(cfg *config.AppConfig, attempts LoginAttemptStore, httpClient *http.Client) (*GitHubLogin, error) {
	if len(cfg.GitHubRedirectURIs) == 0 {
		return nil, nil
	}
	if cfg.GitHubClientID == "" || cfg.GitHubClientSecret == "" || attempts == nil {
		return nil, errors.New("GitHub client credentials and attempt storage are required")
	}
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 5 * time.Second}
	}
	redirects := make(map[string]bool, len(cfg.GitHubRedirectURIs))
	for _, redirect := range cfg.GitHubRedirectURIs {
		redirects[redirect] = true
	}
	return &GitHubLogin{clientID: cfg.GitHubClientID, clientSecret: cfg.GitHubClientSecret, redirects: redirects,
		attempts: attempts, http: httpClient, now: time.Now}, nil
}

func (g *GitHubLogin) AllowsOrigin(origin string) bool {
	for redirect := range g.redirects {
		u, err := url.Parse(redirect)
		if err == nil && (u.Scheme == "https" || u.Scheme == "http") && origin == u.Scheme+"://"+u.Host {
			return true
		}
	}
	return false
}

// Begin creates a login attempt. A positive linkUserID makes it a link attempt for that cowork user.
func (g *GitHubLogin) Begin(ctx context.Context, redirectURI string, linkUserID int64) (string, string, error) {
	if !g.redirects[redirectURI] {
		return "", "", loginFailure("redirect_uri", ErrInvalidLoginRequest)
	}
	state, verifier := oauth.NewCodeVerifier(), oauth2.GenerateVerifier()
	authorizationURL := g.oauthConfig(redirectURI).AuthCodeURL(state, oauth2.S256ChallengeOption(verifier))
	session, err := saveLoginAttempt(ctx, g.attempts, LoginAttempt{Provider: ProviderGitHub,
		Authorization: oauth.Authorization{URL: authorizationURL, State: state, CodeVerifier: verifier},
		RedirectURI:   redirectURI, ExpiresAt: g.now().Add(LoginAttemptTTL), LinkUserID: linkUserID})
	if err != nil {
		return "", "", err
	}
	return authorizationURL, session, nil
}

func (g *GitHubLogin) CompleteAttempt(ctx context.Context, attempt LoginAttempt, callback url.Values) (*domain.GitHubIdentity, error) {
	if attempt.Provider != ProviderGitHub || !g.redirects[attempt.RedirectURI] {
		return nil, loginFailure("attempt_purpose", ErrAuthenticationRejected)
	}
	code, err := attempt.Authorization.Code(callback)
	if err != nil {
		return nil, loginFailure("callback_validation", ErrAuthenticationRejected)
	}
	token, err := g.oauthConfig(attempt.RedirectURI).Exchange(context.WithValue(ctx, oauth2.HTTPClient, g.http), code,
		oauth2.VerifierOption(attempt.Authorization.CodeVerifier))
	if err != nil {
		return nil, loginFailure("token_exchange", g.exchangeError(err))
	}
	client, err := github.NewClient(github.WithHTTPClient(g.http), github.WithAuthToken(token.AccessToken),
		github.WithUserAgent("cowork-authorization"))
	if err != nil {
		return nil, loginFailure("userinfo_request", ErrAuthenticationUnavailable)
	}
	user, _, err := client.Users.Get(ctx, "")
	if err != nil {
		return nil, loginFailure("userinfo_request", g.apiError(err))
	}
	if user.GetID() <= 0 || !domain.ValidGitHubLogin(user.GetLogin()) {
		return nil, loginFailure("userinfo_account", ErrAuthenticationRejected)
	}
	// A verified secondary address can require DataGSM even when the primary address is private.
	email, gsm := "", false
	for page := 1; page != 0; {
		emails, response, err := client.Users.ListEmails(ctx, &github.ListOptions{Page: page, PerPage: 100})
		if err != nil {
			return nil, loginFailure("email_request", g.apiError(err))
		}
		for _, candidate := range emails {
			if !candidate.GetVerified() {
				continue
			}
			address := strings.TrimSpace(candidate.GetEmail())
			gsm = gsm || isGSMSchoolEmail(address)
			if candidate.GetPrimary() {
				email = address
			}
		}
		if gsm && email != "" {
			break
		}
		page = response.NextPage
	}
	if email == "" || utf8.RuneCountInString(email) > 255 {
		return nil, loginFailure("email_unverified", ErrAuthenticationRejected)
	}
	return &domain.GitHubIdentity{ID: user.GetID(), Login: user.GetLogin(), Name: githubDisplayName(user.Name, user.GetLogin()), Email: email,
		GSMEmailVerified: gsm}, nil
}

func isGSMSchoolEmail(address string) bool {
	at := strings.LastIndexByte(address, '@')
	return at > 0 && strings.EqualFold(address[at+1:], "gsm.hs.kr")
}

func (g *GitHubLogin) oauthConfig(redirectURI string) *oauth2.Config {
	endpoint := endpoints.GitHub
	endpoint.AuthStyle = oauth2.AuthStyleInParams
	return &oauth2.Config{ClientID: g.clientID, ClientSecret: g.clientSecret, Endpoint: endpoint,
		RedirectURL: redirectURI, Scopes: []string{githubScope}}
}

// The SDK errors can contain GitHub response text, so callers receive only a classified error.
func (g *GitHubLogin) exchangeError(err error) error {
	var retrieve *oauth2.RetrieveError
	if !errors.As(err, &retrieve) {
		return ErrAuthenticationUnavailable
	}
	switch {
	case retrieve.Response != nil && retrieve.Response.StatusCode == http.StatusTooManyRequests:
		return &ProviderRateLimitError{RetryAfter: retrieve.Response.Header.Get("Retry-After")}
	case retrieve.ErrorCode == "bad_verification_code" || retrieve.ErrorCode == "access_denied":
		return ErrAuthenticationRejected
	}
	return ErrAuthenticationUnavailable
}

func (g *GitHubLogin) apiError(err error) error {
	var limited *github.RateLimitError
	var abuse *github.AbuseRateLimitError
	var response *github.ErrorResponse
	switch {
	case errors.As(err, &limited):
		return &ProviderRateLimitError{RetryAfter: secondsUntil(limited.Rate.Reset.Time, g.now())}
	case errors.As(err, &abuse):
		if abuse.RetryAfter != nil {
			return &ProviderRateLimitError{RetryAfter: strconv.FormatInt(int64(abuse.RetryAfter.Round(time.Second)/time.Second), 10)}
		}
		return &ProviderRateLimitError{}
	case errors.As(err, &response) && response.Response != nil:
		switch response.Response.StatusCode {
		case http.StatusUnauthorized, http.StatusForbidden, http.StatusNotFound:
			return ErrAuthenticationRejected
		}
	}
	return ErrAuthenticationUnavailable
}

func secondsUntil(at, now time.Time) string {
	if !at.After(now) {
		return ""
	}
	return strconv.FormatInt(int64(at.Sub(now).Round(time.Second)/time.Second), 10)
}

// githubDisplayName uses the profile name, or the login when the name is empty, within the 50-character limit.
func githubDisplayName(name *string, login string) string {
	value := login
	if name != nil && strings.TrimSpace(*name) != "" {
		value = strings.TrimSpace(*name)
	}
	if runes := []rune(value); len(runes) > 50 {
		value = strings.TrimSpace(string(runes[:50]))
	}
	return value
}
