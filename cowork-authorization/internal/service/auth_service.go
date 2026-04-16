package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/cowork/authorization/internal/client"
	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"github.com/cowork/authorization/internal/repository"
	"github.com/google/uuid"
	"golang.org/x/oauth2"
	"gorm.io/gorm"
)

type TokenPair struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	TokenType    string `json:"token_type"`
	ExpiresIn    int    `json:"expires_in"`
}

type DataGSMUserInfo struct {
	ID       string  `json:"id"`
	Email    string  `json:"email"`
	Name     string  `json:"name"`
	Sex      string  `json:"sex"`
	Grade    *int8   `json:"grade"`
	Class    *int8   `json:"class"`
	ClassNum *int8   `json:"number"`
	Major    string  `json:"major"`
	GsmRole  string  `json:"role"`
	GithubID *string `json:"githubId"`
}

type AuthService struct {
	cfg              *config.AppConfig
	oauth2Config     *oauth2.Config
	userClient       *client.UserClient
	refreshTokenRepo *repository.RefreshTokenRepository
	tokenSvc         *TokenService
}

func NewAuthService(
	cfg *config.AppConfig,
	userClient *client.UserClient,
	refreshTokenRepo *repository.RefreshTokenRepository,
	tokenSvc *TokenService,
) *AuthService {
	oauth2Cfg := &oauth2.Config{
		ClientID:     cfg.DataGSMClientID,
		ClientSecret: cfg.DataGSMClientSecret,
		RedirectURL:  cfg.DataGSMRedirectURL,
		Scopes:       []string{"openid", "profile", "email"},
		Endpoint: oauth2.Endpoint{
			AuthURL:  cfg.DataGSMAuthURL,
			TokenURL: cfg.DataGSMTokenURL,
		},
	}

	return &AuthService{
		cfg:              cfg,
		oauth2Config:     oauth2Cfg,
		userClient:       userClient,
		refreshTokenRepo: refreshTokenRepo,
		tokenSvc:         tokenSvc,
	}
}

func (s *AuthService) GetLoginURL() (authURL, state string) {
	state = uuid.NewString()
	authURL = s.oauth2Config.AuthCodeURL(state, oauth2.AccessTypeOnline)
	return authURL, state
}

func (s *AuthService) HandleCallback(ctx context.Context, code, state, cookieState string) (*TokenPair, error) {
	if state != cookieState {
		return nil, fmt.Errorf("invalid oauth state")
	}

	token, err := s.oauth2Config.Exchange(ctx, code)
	if err != nil {
		return nil, fmt.Errorf("failed to exchange code: %w", err)
	}

	userInfo, err := s.fetchUserInfo(ctx, token.AccessToken)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch user info: %w", err)
	}

	datgasmUserID, err := strconv.ParseInt(userInfo.ID, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("failed to parse DataGSM user ID: %w", err)
	}

	upsertReq := client.UpsertUserRequest{
		Name:     userInfo.Name,
		Email:    userInfo.Email,
		Sex:      userInfo.Sex,
		Grade:    userInfo.Grade,
		Class:    userInfo.Class,
		ClassNum: userInfo.ClassNum,
		Major:    userInfo.Major,
		Role:     userInfo.GsmRole,
		GithubID: userInfo.GithubID,
	}

	userID, err := s.userClient.Upsert(ctx, datgasmUserID, upsertReq)
	if err != nil {
		return nil, fmt.Errorf("failed to upsert user: %w", err)
	}

	return s.issueTokenPair(userID, userInfo.Email, "MEMBER", userInfo.GsmRole, "")
}

func (s *AuthService) RefreshTokens(rawRefreshToken string) (*TokenPair, error) {
	hash := HashToken(rawRefreshToken)

	rt, err := s.refreshTokenRepo.FindByHash(hash)
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, fmt.Errorf("refresh token not found")
		}
		return nil, fmt.Errorf("failed to find refresh token: %w", err)
	}

	if time.Now().After(rt.ExpiresAt) {
		return nil, fmt.Errorf("refresh token expired")
	}

	if err := s.refreshTokenRepo.DeleteByHash(hash); err != nil {
		return nil, fmt.Errorf("failed to delete old refresh token: %w", err)
	}

	var deviceInfo string
	if rt.DeviceInfo != nil {
		deviceInfo = *rt.DeviceInfo
	}
	return s.issueTokenPair(rt.UserID, rt.Email, "MEMBER", rt.GsmRole, deviceInfo)
}

func (s *AuthService) Logout(rawRefreshToken string) error {
	hash := HashToken(rawRefreshToken)
	return s.refreshTokenRepo.DeleteByHash(hash)
}

func (s *AuthService) issueTokenPair(userID int64, email, role, gsmRole, deviceInfo string) (*TokenPair, error) {
	accessToken, err := s.tokenSvc.GenerateAccessToken(userID, email, role, gsmRole)
	if err != nil {
		return nil, fmt.Errorf("failed to generate access token: %w", err)
	}

	rawRefresh, refreshHash, err := s.tokenSvc.GenerateRefreshToken()
	if err != nil {
		return nil, fmt.Errorf("failed to generate refresh token: %w", err)
	}

	rt := &domain.RefreshToken{
		UserID:    userID,
		TokenHash: refreshHash,
		Email:     email,
		GsmRole:   gsmRole,
		ExpiresAt: time.Now().Add(s.tokenSvc.RefreshExpire()),
	}
	if deviceInfo != "" {
		rt.DeviceInfo = &deviceInfo
	}

	if err := s.refreshTokenRepo.Create(rt); err != nil {
		return nil, fmt.Errorf("failed to store refresh token: %w", err)
	}

	return &TokenPair{
		AccessToken:  accessToken,
		RefreshToken: rawRefresh,
		TokenType:    "Bearer",
		ExpiresIn:    int(s.cfg.JWTAccessExpire.Seconds()),
	}, nil
}

func (s *AuthService) fetchUserInfo(ctx context.Context, accessToken string) (*DataGSMUserInfo, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.cfg.DataGSMUserInfoURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("userinfo endpoint returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var info DataGSMUserInfo
	if err := json.Unmarshal(body, &info); err != nil {
		return nil, fmt.Errorf("failed to parse userinfo response: %w", err)
	}
	return &info, nil
}
