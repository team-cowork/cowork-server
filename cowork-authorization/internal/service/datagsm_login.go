package service

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/cowork/authorization/internal/config"
	datagsm "github.com/tmp-it-play/datagsm-sdk-go"
	"github.com/tmp-it-play/datagsm-sdk-go/oauth"
)

const LoginAttemptTTL = 10 * time.Minute

var (
	ErrInvalidLoginRequest    = errors.New("invalid login request")
	ErrAuthenticationRejected = errors.New("authentication failed")
	ErrLoginAttemptNotFound   = errors.New("login attempt not found")
)

type ProviderRateLimitError struct{ RetryAfter string }

func (*ProviderRateLimitError) Error() string { return "authentication rate limit exceeded" }

type LoginAttempt struct {
	Authorization oauth.Authorization `json:"authorization"`
	RedirectURI   string              `json:"redirect_uri"`
	ExpiresAt     time.Time           `json:"expires_at"`
}

type LoginAttemptStore interface {
	Save(context.Context, string, LoginAttempt, time.Duration) error
	Take(context.Context, string) (LoginAttempt, error)
}

type DataGSMLogin struct {
	clients         map[string]*oauth.Client
	issuer          string
	clientID        string
	attempts        LoginAttemptStore
	now             func() time.Time
	mu              sync.Mutex
	keys            *oauth.JWKS
	keysFetchedAt   time.Time
	keysAttemptedAt time.Time
}

func NewDataGSMLogin(cfg *config.AppConfig, attempts LoginAttemptStore, httpClient *http.Client) (*DataGSMLogin, error) {
	if len(cfg.DataGSMRedirectURIs) == 0 || attempts == nil {
		return nil, errors.New("OAuth redirect URIs and attempt storage are required")
	}
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 5 * time.Second}
	}
	issuer := cfg.DataGSMAuthorizationURL
	if issuer == "" {
		issuer = oauth.DefaultAuthorizationURL
	}
	s := &DataGSMLogin{clients: make(map[string]*oauth.Client), issuer: strings.TrimRight(issuer, "/"),
		clientID: cfg.DataGSMClientID, attempts: attempts, now: time.Now}
	for _, redirect := range cfg.DataGSMRedirectURIs {
		client, err := oauth.NewClient(oauth.Config{ClientID: cfg.DataGSMClientID, AuthMethod: oauth.AuthNone,
			RedirectURI: redirect, AuthorizationBaseURL: issuer, ResourceBaseURL: cfg.DataGSMResourceURL, HTTPClient: httpClient})
		if err != nil {
			s.Close()
			return nil, err
		}
		s.clients[redirect] = client
	}
	return s, nil
}

func (s *DataGSMLogin) Close() {
	for _, client := range s.clients {
		client.Close()
	}
}

func (s *DataGSMLogin) AllowsOrigin(origin string) bool {
	// Native clients have no Origin. Browser requests must come from a registered web callback origin.
	if origin == "" {
		return true
	}
	for redirect := range s.clients {
		u, err := url.Parse(redirect)
		if err == nil && (u.Scheme == "https" || u.Scheme == "http") && origin == u.Scheme+"://"+u.Host {
			return true
		}
	}
	return false
}

func (s *DataGSMLogin) Begin(ctx context.Context, redirectURI string) (string, string, error) {
	client := s.clients[redirectURI]
	if client == nil {
		return "", "", ErrInvalidLoginRequest
	}
	attempt, err := client.NewAuthorization(oauth.ScopeOpenID, oauth.ScopeAccountRead, oauth.ScopeStudentRead)
	if err != nil {
		return "", "", ErrAuthenticationUnavailable
	}
	session := oauth.NewCodeVerifier()
	if err := s.attempts.Save(ctx, HashToken(session), LoginAttempt{Authorization: *attempt,
		RedirectURI: redirectURI, ExpiresAt: s.now().Add(LoginAttemptTTL)}, LoginAttemptTTL); err != nil {
		return "", "", ErrAuthenticationUnavailable
	}
	return attempt.URL, session, nil
}

func (s *DataGSMLogin) Complete(ctx context.Context, session string, callback url.Values) (*oauth.UserInfo, error) {
	if len(session) != 43 {
		return nil, ErrAuthenticationRejected
	}
	if _, err := oauth.CodeChallenge(session); err != nil {
		return nil, ErrAuthenticationRejected
	}
	// The independent HttpOnly cookie binds the public state to the initiating client.
	attempt, err := s.attempts.Take(ctx, HashToken(session))
	if errors.Is(err, ErrLoginAttemptNotFound) {
		return nil, ErrAuthenticationRejected
	}
	if err != nil {
		return nil, ErrAuthenticationUnavailable
	}
	client := s.clients[attempt.RedirectURI]
	if client == nil || !s.now().Before(attempt.ExpiresAt) {
		return nil, ErrAuthenticationRejected
	}
	code, err := attempt.Authorization.Code(callback)
	if err != nil {
		return nil, ErrAuthenticationRejected
	}
	token, err := client.Exchange(ctx, oauth.CodeExchange{Code: code, CodeVerifier: attempt.Authorization.CodeVerifier})
	if err != nil {
		return nil, providerError(err)
	}
	if token.IDToken == nil {
		return nil, ErrAuthenticationRejected
	}
	scopes := strings.Fields(token.Scope)
	for _, required := range []string{oauth.ScopeOpenID, oauth.ScopeAccountRead, oauth.ScopeStudentRead} {
		found := false
		for _, scope := range scopes {
			if scope == required {
				found = true
				break
			}
		}
		if !found {
			return nil, ErrAuthenticationUnavailable
		}
	}
	keys, err := s.jwks(ctx, client, false)
	if err != nil {
		return nil, err
	}
	claims, err := keys.VerifyIDToken(*token.IDToken, s.issuer, s.clientID, attempt.Authorization.Nonce, s.now())
	if err != nil {
		// Refresh for key rotation, with a cooldown so invalid tokens cannot trigger a fetch storm.
		keys, fetchErr := s.jwks(ctx, client, true)
		if fetchErr != nil {
			return nil, fetchErr
		}
		claims, err = keys.VerifyIDToken(*token.IDToken, s.issuer, s.clientID, attempt.Authorization.Nonce, s.now())
		if err != nil {
			return nil, ErrAuthenticationRejected
		}
	}
	user, err := client.UserInfo(ctx, token.AccessToken)
	if err != nil {
		return nil, providerError(err)
	}
	if user.Subject != claims.Subject || user.ID <= 0 || user.Status != "ACTIVE" {
		return nil, ErrAuthenticationRejected
	}
	return user, nil
}

// Exchange supports existing clients that own their PKCE attempt and state validation.
// Server-created attempts never disclose their verifier and use Complete instead.
// Deprecated: use Begin and Complete for server-managed OAuth/OIDC.
// TODO(legacy-login): Remove this SDK compatibility path after the web and mobile implementations migrate to server-managed OAuth/OIDC.
func (s *DataGSMLogin) Exchange(ctx context.Context, code, verifier, redirectURI string) (*oauth.UserInfo, error) {
	client := s.clients[redirectURI]
	if client == nil || code == "" {
		return nil, ErrInvalidLoginRequest
	}
	if _, err := oauth.CodeChallenge(verifier); err != nil {
		return nil, ErrInvalidLoginRequest
	}
	token, err := client.Exchange(ctx, oauth.CodeExchange{Code: code, CodeVerifier: verifier})
	if err != nil {
		return nil, providerError(err)
	}
	user, err := client.UserInfo(ctx, token.AccessToken)
	if err != nil {
		return nil, providerError(err)
	}
	if user.ID <= 0 || user.Status != "ACTIVE" {
		return nil, ErrAuthenticationRejected
	}
	return user, nil
}

func (s *DataGSMLogin) jwks(ctx context.Context, client *oauth.Client, refresh bool) (*oauth.JWKS, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	if s.keys != nil && now.Sub(s.keysFetchedAt) < 15*time.Minute && (!refresh || now.Sub(s.keysAttemptedAt) < 30*time.Second) {
		return s.keys, nil
	}
	if now.Sub(s.keysAttemptedAt) < 30*time.Second {
		return nil, ErrAuthenticationUnavailable
	}
	s.keysAttemptedAt = now
	keys, err := client.JWKS(ctx)
	if err != nil {
		return nil, providerError(err)
	}
	if len(keys.Keys) == 0 {
		return nil, ErrAuthenticationUnavailable
	}
	s.keys, s.keysFetchedAt = keys, now
	return keys, nil
}

func providerError(err error) error {
	var apiErr *datagsm.APIError
	if errors.As(err, &apiErr) {
		if apiErr.StatusCode == http.StatusTooManyRequests {
			return &ProviderRateLimitError{RetryAfter: apiErr.RetryAfter}
		}
		if apiErr.Code == "invalid_grant" || apiErr.Code == "access_denied" {
			return ErrAuthenticationRejected
		}
	}
	// SDK errors can contain provider response messages. Never expose them in an auth response or log.
	return ErrAuthenticationUnavailable
}
