package service

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"gorm.io/gorm"

	"github.com/cowork/authorization/internal/domain"
)

const (
	AgentTokenPrefix           = "cwa_"
	MaxAgentTokenLifetimeDays  = 365
	maxAgentTokenNameRuneCount = 100
	// MaxActiveAgentTokensPerUser bounds how many usable tokens one user can hold at a time.
	MaxActiveAgentTokensPerUser = 20
)

var (
	ErrInvalidAgentTokenRequest = errors.New("invalid agent token request")
	ErrAgentTokenNotFound       = errors.New("agent token not found")
	ErrAgentTokenRejected       = errors.New("agent token is invalid, expired or revoked")
	ErrAgentTokenLimitExceeded  = errors.New("agent token limit exceeded")
)

type AgentTokenStore interface {
	Create(ctx context.Context, token *domain.AgentToken) error
	ListByUser(ctx context.Context, userID int64) ([]domain.AgentToken, error)
	CountActiveByUser(ctx context.Context, userID int64, at time.Time) (int64, error)
	FindByHash(ctx context.Context, hash string) (*domain.AgentToken, error)
	Revoke(ctx context.Context, id, userID int64, at time.Time) (bool, error)
	MarkUsed(ctx context.Context, id int64, at time.Time) (bool, error)
}

type AgentTokenInfo struct {
	ID         int64      `json:"id"`
	Name       string     `json:"name"`
	Scopes     []string   `json:"scopes"`
	ExpiresAt  time.Time  `json:"expires_at"`
	LastUsedAt *time.Time `json:"last_used_at"`
	RevokedAt  *time.Time `json:"revoked_at"`
	CreatedAt  time.Time  `json:"created_at"`
}

type IssuedAgentToken struct {
	AgentTokenInfo
	// Token is returned only once. The store keeps only its hash.
	Token string `json:"token"`
}

type AgentAccessToken struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int    `json:"expires_in"`
}

type AgentTokenService struct {
	store    AgentTokenStore
	tokenSvc *TokenService
	now      func() time.Time
}

func NewAgentTokenService(store AgentTokenStore, tokenSvc *TokenService) *AgentTokenService {
	return &AgentTokenService{store: store, tokenSvc: tokenSvc, now: time.Now}
}

func (s *AgentTokenService) Issue(
	ctx context.Context,
	userID int64,
	name string,
	scopes []string,
	expiresInDays int,
) (*IssuedAgentToken, error) {
	name = strings.TrimSpace(name)
	if name == "" || utf8.RuneCountInString(name) > maxAgentTokenNameRuneCount ||
		expiresInDays < 1 || expiresInDays > MaxAgentTokenLifetimeDays {
		return nil, ErrInvalidAgentTokenRequest
	}
	normalized, err := domain.NormalizeAgentScopes(scopes)
	if err != nil {
		return nil, ErrInvalidAgentTokenRequest
	}
	now := s.now().UTC().Truncate(time.Microsecond)
	// shortcut: concurrent issues can pass the count together and exceed the limit by a few;
	// lock the user's rows in a transaction if that matters.
	active, err := s.store.CountActiveByUser(ctx, userID, now)
	if err != nil {
		return nil, fmt.Errorf("failed to count agent tokens: %w", err)
	}
	if active >= MaxActiveAgentTokensPerUser {
		return nil, ErrAgentTokenLimitExceeded
	}
	raw, hash, err := s.tokenSvc.GenerateAgentToken()
	if err != nil {
		return nil, err
	}

	token := &domain.AgentToken{
		UserID:    userID,
		Name:      name,
		TokenHash: hash,
		Scopes:    domain.JoinAgentScopes(normalized),
		ExpiresAt: now.AddDate(0, 0, expiresInDays),
		CreatedAt: now,
	}
	if err := s.store.Create(ctx, token); err != nil {
		return nil, fmt.Errorf("failed to store agent token: %w", err)
	}
	return &IssuedAgentToken{AgentTokenInfo: agentTokenInfo(*token), Token: raw}, nil
}

func (s *AgentTokenService) List(ctx context.Context, userID int64) ([]AgentTokenInfo, error) {
	tokens, err := s.store.ListByUser(ctx, userID)
	if err != nil {
		return nil, err
	}
	infos := make([]AgentTokenInfo, len(tokens))
	for index, token := range tokens {
		infos[index] = agentTokenInfo(token)
	}
	return infos, nil
}

func (s *AgentTokenService) Revoke(ctx context.Context, userID, id int64) error {
	revoked, err := s.store.Revoke(ctx, id, userID, s.now().UTC().Truncate(time.Microsecond))
	if err != nil {
		return err
	}
	if !revoked {
		return ErrAgentTokenNotFound
	}
	return nil
}

// Exchange signs a short access JWT for an unrevoked and unexpired agent token.
// The JWT never outlives the agent token. Revocation does not reach JWTs exchanged before it.
// The use is recorded with a conditional update before signing, so a revocation that lands
// after the read still stops the exchange.
func (s *AgentTokenService) Exchange(ctx context.Context, raw string) (*AgentAccessToken, error) {
	if !strings.HasPrefix(raw, AgentTokenPrefix) {
		return nil, ErrAgentTokenRejected
	}
	token, err := s.store.FindByHash(ctx, HashToken(raw))
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrAgentTokenRejected
	}
	if err != nil {
		return nil, err
	}

	usedAt := s.now().UTC().Truncate(time.Microsecond)
	now := usedAt.Truncate(time.Second)
	if !token.Exchangeable(now) {
		return nil, ErrAgentTokenRejected
	}
	expiresAt := now.Add(s.tokenSvc.AccessExpire())
	if token.ExpiresAt.Before(expiresAt) {
		expiresAt = token.ExpiresAt.UTC().Truncate(time.Second)
	}
	if !expiresAt.After(now) {
		return nil, ErrAgentTokenRejected
	}

	used, err := s.store.MarkUsed(ctx, token.ID, usedAt)
	if err != nil {
		return nil, fmt.Errorf("failed to record agent token use: %w", err)
	}
	if !used {
		return nil, ErrAgentTokenRejected
	}
	accessToken, err := s.tokenSvc.GenerateAgentAccessToken(token.UserID, token.Scopes, now, expiresAt)
	if err != nil {
		return nil, fmt.Errorf("failed to generate agent access token: %w", err)
	}
	return &AgentAccessToken{
		AccessToken: accessToken,
		TokenType:   "Bearer",
		ExpiresIn:   int(expiresAt.Sub(now).Seconds()),
	}, nil
}

func agentTokenInfo(token domain.AgentToken) AgentTokenInfo {
	return AgentTokenInfo{
		ID:         token.ID,
		Name:       token.Name,
		Scopes:     strings.Fields(token.Scopes),
		ExpiresAt:  token.ExpiresAt,
		LastUsedAt: token.LastUsedAt,
		RevokedAt:  token.RevokedAt,
		CreatedAt:  token.CreatedAt,
	}
}
