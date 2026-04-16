package service

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/golang-jwt/jwt/v5"
)

type Claims struct {
	jwt.RegisteredClaims
	Email   string `json:"email"`
	Role    string `json:"role"`
	GsmRole string `json:"gsm_role"`
}

type TokenService struct {
	cfg *config.AppConfig
}

func NewTokenService(cfg *config.AppConfig) *TokenService {
	return &TokenService{cfg: cfg}
}

func (s *TokenService) GenerateAccessToken(userID int64, email, role, gsmRole string) (string, error) {
	now := time.Now()
	claims := Claims{
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   fmt.Sprintf("%d", userID),
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(s.cfg.JWTAccessExpire)),
		},
		Email:   email,
		Role:    role,
		GsmRole: gsmRole,
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(s.cfg.JWTSecret))
}

func (s *TokenService) ValidateAccessToken(tokenStr string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(tokenStr, &Claims{}, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return []byte(s.cfg.JWTSecret), nil
	})
	if err != nil {
		return nil, err
	}

	claims, ok := token.Claims.(*Claims)
	if !ok || !token.Valid {
		return nil, fmt.Errorf("invalid token")
	}
	return claims, nil
}

func (s *TokenService) GenerateRefreshToken() (rawToken, tokenHash string, err error) {
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return "", "", fmt.Errorf("failed to generate refresh token: %w", err)
	}
	rawToken = hex.EncodeToString(b)
	tokenHash = hashToken(rawToken)
	return rawToken, tokenHash, nil
}

func (s *TokenService) RefreshExpire() time.Duration {
	return s.cfg.JWTRefreshExpire
}

func hashToken(raw string) string {
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

func HashToken(raw string) string {
	return hashToken(raw)
}
