package service

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
)

type Claims struct {
	jwt.RegisteredClaims
	Email   string `json:"email"`
	GsmRole string `json:"gsm_role"`
	// Login JWTs omit both claims, so Gateway keeps treating them as full user sessions.
	TokenUse string `json:"token_use,omitempty"`
	Scope    string `json:"scope,omitempty"`
}

type TokenService struct {
	cfg *config.AppConfig
}

func NewTokenService(cfg *config.AppConfig) *TokenService {
	return &TokenService{cfg: cfg}
}

func (s *TokenService) GenerateAccessToken(userID int64, email, gsmRole string) (string, error) {
	now := time.Now()
	claims := Claims{
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   fmt.Sprintf("%d", userID),
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(s.cfg.JWTAccessExpire)),
		},
		Email:   email,
		GsmRole: gsmRole,
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(s.cfg.JWTSecret))
}

// GenerateAgentAccessToken signs an access JWT that Gateway limits to the given space-separated scopes.
func (s *TokenService) GenerateAgentAccessToken(userID int64, scope string, issuedAt, expiresAt time.Time) (string, error) {
	claims := Claims{
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   fmt.Sprintf("%d", userID),
			IssuedAt:  jwt.NewNumericDate(issuedAt),
			ExpiresAt: jwt.NewNumericDate(expiresAt),
		},
		TokenUse: domain.AgentTokenUse,
		Scope:    scope,
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(s.cfg.JWTSecret))
}

func (s *TokenService) AccessExpire() time.Duration {
	return s.cfg.JWTAccessExpire
}

func (s *TokenService) GenerateRefreshToken() (rawToken, tokenHash string, err error) {
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return "", "", fmt.Errorf("failed to generate refresh token: %w", err)
	}
	rawToken = hex.EncodeToString(b)
	tokenHash = HashToken(rawToken)
	return rawToken, tokenHash, nil
}

// GenerateAgentToken returns a prefixed raw token so that secret scanners can recognize a leaked one.
func (s *TokenService) GenerateAgentToken() (rawToken, tokenHash string, err error) {
	random, _, err := s.GenerateRefreshToken()
	if err != nil {
		return "", "", err
	}
	rawToken = AgentTokenPrefix + random
	return rawToken, HashToken(rawToken), nil
}

func (s *TokenService) RefreshExpire() time.Duration {
	return s.cfg.JWTRefreshExpire
}

func HashToken(raw string) string {
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}
