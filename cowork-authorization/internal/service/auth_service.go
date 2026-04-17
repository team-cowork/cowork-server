package service

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
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
	ID        int64           `json:"id"`
	Email     string          `json:"email"`
	Role      string          `json:"role"` // USER, ADMIN
	IsStudent bool            `json:"isStudent"`
	Student   *DataGSMStudent `json:"student"`
}

type DataGSMStudent struct {
	ID            int64   `json:"id"`
	Name          string  `json:"name"`
	Sex           string  `json:"sex"`
	Grade         int8    `json:"grade"`
	ClassNum      int8    `json:"classNum"` // 반
	Number        int8    `json:"number"`   // 번호
	Major         string  `json:"major"`
	Specialty     *string `json:"specialty"`
	GithubID      *string `json:"githubId"`
	Role          string  `json:"role"` // GENERAL_STUDENT, STUDENT_COUNCIL, ...
	IsLeaveSchool bool    `json:"isLeaveSchool"`
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

	accessToken, err := s.exchangeCode(ctx, code)
	if err != nil {
		return nil, fmt.Errorf("failed to exchange code: %w", err)
	}

	userInfo, err := s.fetchUserInfo(ctx, accessToken)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch user info: %w", err)
	}

	if !userInfo.IsStudent || userInfo.Student == nil {
		return nil, fmt.Errorf("non-student users are not supported")
	}

	st := userInfo.Student
	grade := st.Grade
	classNum := st.ClassNum
	number := st.Number

	upsertReq := client.UpsertUserRequest{
		Name:     st.Name,
		Email:    userInfo.Email,
		Sex:      st.Sex,
		Grade:    &grade,
		Class:    &classNum,
		ClassNum: &number,
		Major:    st.Major,
		Role:     st.Role,
		GithubID: st.GithubID,
	}

	userID, err := s.userClient.Upsert(ctx, userInfo.ID, upsertReq)
	if err != nil {
		return nil, fmt.Errorf("failed to upsert user: %w", err)
	}

	return s.issueTokenPair(userID, userInfo.Email, "MEMBER", st.Role, "")
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

func (s *AuthService) exchangeCode(ctx context.Context, code string) (string, error) {
	body, _ := json.Marshal(map[string]string{
		"grant_type":    "authorization_code",
		"code":          code,
		"client_id":     s.cfg.DataGSMClientID,
		"client_secret": s.cfg.DataGSMClientSecret,
		"redirect_uri":  s.cfg.DataGSMRedirectURL,
	})

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.cfg.DataGSMTokenURL, bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := (&http.Client{Timeout: 5 * time.Second}).Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("token endpoint returned %d: %s", resp.StatusCode, respBody)
	}

	var result struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.Unmarshal(respBody, &result); err != nil {
		return "", fmt.Errorf("failed to parse token response: %w", err)
	}
	if result.AccessToken == "" {
		return "", fmt.Errorf("empty access_token in response")
	}
	return result.AccessToken, nil
}

func (s *AuthService) fetchUserInfo(ctx context.Context, accessToken string) (*DataGSMUserInfo, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.cfg.DataGSMUserInfoURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := (&http.Client{Timeout: 5 * time.Second}).Do(req)
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
