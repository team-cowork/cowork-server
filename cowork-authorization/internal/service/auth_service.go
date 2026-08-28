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

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"gorm.io/gorm"
)

var ErrAuthenticationUnavailable = errors.New("authentication temporarily unavailable")

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

type RefreshTokenStore interface {
	CreateSession(
		ctx context.Context,
		token *domain.RefreshToken,
		occurredAt time.Time,
		topic string,
	) error
	RotateSession(
		ctx context.Context,
		oldHash string,
		newHash string,
		newExpiresAt time.Time,
		now time.Time,
	) (*domain.RefreshToken, error)
	RevokeSession(
		ctx context.Context,
		hash string,
		userID int64,
		occurredAt time.Time,
		topic string,
	) error
}

type IdentityCommitCoordinator interface {
	EnsureUser(context.Context, domain.UserIdentityCommand) (int64, error)
}

type AuthService struct {
	cfg              *config.AppConfig
	httpClient       *http.Client
	identity         IdentityCommitCoordinator
	refreshTokenRepo RefreshTokenStore
	tokenSvc         *TokenService
	now              func() time.Time
}

func NewAuthService(
	cfg *config.AppConfig,
	identity IdentityCommitCoordinator,
	refreshTokenRepo RefreshTokenStore,
	tokenSvc *TokenService,
) *AuthService {
	return &AuthService{
		cfg:              cfg,
		httpClient:       &http.Client{Timeout: 5 * time.Second},
		identity:         identity,
		refreshTokenRepo: refreshTokenRepo,
		tokenSvc:         tokenSvc,
		now: func() time.Time {
			return time.Now().UTC().Truncate(time.Microsecond)
		},
	}
}

func (s *AuthService) ExchangeCode(ctx context.Context, code, codeVerifier, redirectURI string) (*TokenPair, error) {
	accessToken, err := s.exchangeCode(ctx, code, codeVerifier, redirectURI)
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
	platformRole, err := PlatformRoleFromDataGSM(userInfo.Role)
	if err != nil {
		return nil, err
	}
	operationID, err := domain.NewUUID()
	if err != nil {
		return nil, fmt.Errorf("failed to create user identity operation: %w", err)
	}
	grade := st.Grade
	classNumber := st.ClassNum
	studentNumberInClass := st.Number
	studentID := st.ID
	command := domain.UserIdentityCommand{
		SchemaVersion:        domain.UserIdentitySchemaVersion,
		OperationID:          operationID,
		IdempotencyKey:       operationID,
		CommandType:          domain.UserIdentityCommandUpsert,
		UserID:               userInfo.ID,
		Name:                 st.Name,
		Email:                userInfo.Email,
		Sex:                  st.Sex,
		Grade:                &grade,
		ClassNumber:          &classNumber,
		StudentNumberInClass: &studentNumberInClass,
		Major:                st.Major,
		Role:                 st.Role,
		GithubID:             st.GithubID,
		DataGSMStudentID:     &studentID,
		RequestedBy:          userInfo.ID,
		OccurredAt:           s.now().UTC().Truncate(time.Microsecond),
	}
	return s.commitIdentityAndIssueSession(
		ctx,
		command,
		userInfo.Email,
		platformRole,
		st.Role,
		"",
	)
}

func (s *AuthService) RefreshTokens(ctx context.Context, rawRefreshToken string) (*TokenPair, error) {
	rawRefresh, refreshHash, err := s.tokenSvc.GenerateRefreshToken()
	if err != nil {
		return nil, fmt.Errorf("%w: failed to generate refresh token: %v", ErrAuthenticationUnavailable, err)
	}

	now := s.now().UTC().Truncate(time.Microsecond)
	source, err := s.refreshTokenRepo.RotateSession(
		ctx,
		HashToken(rawRefreshToken),
		refreshHash,
		now.Add(s.tokenSvc.RefreshExpire()),
		now,
	)
	if err != nil {
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			return nil, fmt.Errorf("refresh token not found")
		case errors.Is(err, domain.ErrRefreshTokenExpired):
			return nil, fmt.Errorf("refresh token expired")
		default:
			return nil, fmt.Errorf("%w: failed to rotate refresh token: %v", ErrAuthenticationUnavailable, err)
		}
	}

	accessToken, err := s.tokenSvc.GenerateAccessToken(
		source.UserID,
		source.Email,
		source.PlatformRole,
		source.GsmRole,
	)
	if err != nil {
		return nil, fmt.Errorf("%w: failed to generate access token: %v", ErrAuthenticationUnavailable, err)
	}
	return s.tokenPair(accessToken, rawRefresh), nil
}

func (s *AuthService) Logout(ctx context.Context, userID int64, rawRefreshToken string) error {
	err := s.refreshTokenRepo.RevokeSession(
		ctx,
		HashToken(rawRefreshToken),
		userID,
		s.now().UTC().Truncate(time.Microsecond),
		s.cfg.KafkaTopicUserPresence,
	)
	if err != nil {
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			return fmt.Errorf("refresh token not found")
		case errors.Is(err, domain.ErrRefreshTokenOwnerMismatch):
			return fmt.Errorf("token does not belong to user")
		default:
			return fmt.Errorf("%w: failed to revoke refresh token: %v", ErrAuthenticationUnavailable, err)
		}
	}

	return nil
}

func (s *AuthService) issueNewSession(
	ctx context.Context,
	userID int64,
	email string,
	role string,
	gsmRole string,
	deviceInfo string,
) (*TokenPair, error) {
	accessToken, err := s.tokenSvc.GenerateAccessToken(userID, email, role, gsmRole)
	if err != nil {
		return nil, fmt.Errorf("failed to generate access token: %w", err)
	}

	rawRefresh, refreshHash, err := s.tokenSvc.GenerateRefreshToken()
	if err != nil {
		return nil, fmt.Errorf("failed to generate refresh token: %w", err)
	}

	occurredAt := s.now().UTC().Truncate(time.Microsecond)
	rt := &domain.RefreshToken{
		UserID:       userID,
		TokenHash:    refreshHash,
		Email:        email,
		GsmRole:      gsmRole,
		PlatformRole: role,
		ExpiresAt:    occurredAt.Add(s.tokenSvc.RefreshExpire()),
	}
	if deviceInfo != "" {
		rt.DeviceInfo = &deviceInfo
	}

	if err := s.refreshTokenRepo.CreateSession(
		ctx,
		rt,
		occurredAt,
		s.cfg.KafkaTopicUserPresence,
	); err != nil {
		return nil, fmt.Errorf("failed to store refresh session: %w", err)
	}

	return s.tokenPair(accessToken, rawRefresh), nil
}

func (s *AuthService) commitIdentityAndIssueSession(
	ctx context.Context,
	command domain.UserIdentityCommand,
	email string,
	role string,
	gsmRole string,
	deviceInfo string,
) (*TokenPair, error) {
	userID, err := s.identity.EnsureUser(ctx, command)
	if err != nil {
		return nil, fmt.Errorf("failed to commit user identity: %w", err)
	}
	pair, err := s.issueNewSession(ctx, userID, email, role, gsmRole, deviceInfo)
	if err != nil {
		return nil, fmt.Errorf("%w: failed to issue session: %v", ErrAuthenticationUnavailable, err)
	}
	return pair, nil
}

// PlatformRoleFromDataGSM maps the provider's account role to the only two
// platform-wide roles accepted by Gateway and downstream services.
func PlatformRoleFromDataGSM(providerRole string) (string, error) {
	switch providerRole {
	case "ADMIN":
		return "ADMIN", nil
	case "USER":
		return "MEMBER", nil
	default:
		return "", fmt.Errorf("unsupported DataGSM account role")
	}
}

func (s *AuthService) tokenPair(accessToken, rawRefresh string) *TokenPair {
	return &TokenPair{
		AccessToken:  accessToken,
		RefreshToken: rawRefresh,
		TokenType:    "Bearer",
		ExpiresIn:    int(s.cfg.JWTAccessExpire.Seconds()),
	}
}

func (s *AuthService) exchangeCode(ctx context.Context, code, codeVerifier, redirectURI string) (string, error) {
	body, err := json.Marshal(map[string]string{
		"grant_type":    "authorization_code",
		"code":          code,
		"client_id":     s.cfg.DataGSMClientID,
		"redirect_uri":  redirectURI,
		"code_verifier": codeVerifier,
	})
	if err != nil {
		return "", fmt.Errorf("failed to marshal token request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.cfg.DataGSMTokenURL, bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("failed to create token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("%w: failed to call token endpoint: %v", ErrAuthenticationUnavailable, err)
	}
	defer func() { _ = resp.Body.Close() }()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("%w: failed to read token response: %v", ErrAuthenticationUnavailable, err)
	}
	if resp.StatusCode != http.StatusOK {
		return "", providerStatusError("token", resp.StatusCode)
	}

	var result struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.Unmarshal(respBody, &result); err != nil {
		return "", fmt.Errorf("%w: failed to parse token response: %v", ErrAuthenticationUnavailable, err)
	}
	if result.AccessToken == "" {
		return "", fmt.Errorf("%w: empty access_token in response", ErrAuthenticationUnavailable)
	}
	return result.AccessToken, nil
}

func (s *AuthService) fetchUserInfo(ctx context.Context, accessToken string) (*DataGSMUserInfo, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.cfg.DataGSMUserInfoURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create userinfo request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: failed to call userinfo endpoint: %v", ErrAuthenticationUnavailable, err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, providerStatusError("userinfo", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("%w: failed to read userinfo response: %v", ErrAuthenticationUnavailable, err)
	}

	var info DataGSMUserInfo
	if err := json.Unmarshal(body, &info); err != nil {
		return nil, fmt.Errorf("%w: failed to parse userinfo response: %v", ErrAuthenticationUnavailable, err)
	}
	return &info, nil
}

func providerStatusError(endpoint string, status int) error {
	err := fmt.Errorf("%s endpoint returned status %d", endpoint, status)
	if status >= http.StatusInternalServerError {
		return fmt.Errorf("%w: %v", ErrAuthenticationUnavailable, err)
	}
	return err
}
