package service

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"github.com/tmp-it-play/datagsm-sdk-go/oauth"
	"gorm.io/gorm"
)

var ErrAuthenticationUnavailable = errors.New("authentication temporarily unavailable")

type TokenPair struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	TokenType    string `json:"token_type"`
	ExpiresIn    int    `json:"expires_in"`
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
	login            *DataGSMLogin
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
	login *DataGSMLogin,
) *AuthService {
	return &AuthService{
		cfg:              cfg,
		login:            login,
		identity:         identity,
		refreshTokenRepo: refreshTokenRepo,
		tokenSvc:         tokenSvc,
		now: func() time.Time {
			return time.Now().UTC().Truncate(time.Microsecond)
		},
	}
}

func (s *AuthService) BeginLogin(ctx context.Context, redirectURI string) (string, string, error) {
	return s.login.Begin(ctx, redirectURI)
}

func (s *AuthService) AllowsLoginOrigin(origin string) bool { return s.login.AllowsOrigin(origin) }

func (s *AuthService) CompleteLogin(ctx context.Context, session string, callback url.Values) (*TokenPair, error) {
	userInfo, err := s.login.Complete(ctx, session, callback)
	if err != nil {
		return nil, err
	}
	return s.issueUserSession(ctx, userInfo)
}

func (s *AuthService) issueUserSession(ctx context.Context, userInfo *oauth.UserInfo) (*TokenPair, error) {
	if userInfo.ObjectType == nil || *userInfo.ObjectType != "STUDENT" || userInfo.Student == nil {
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
	grade, err := studentInteger(st.Grade)
	if err != nil {
		return nil, err
	}
	classNumber, err := studentInteger(st.ClassNum)
	if err != nil {
		return nil, err
	}
	studentNumberInClass, err := studentInteger(st.Number)
	if err != nil {
		return nil, err
	}
	var major *string
	if st.Major != nil {
		value := string(*st.Major)
		major = &value
	}
	studentID := st.ID
	command := domain.UserIdentityCommand{
		SchemaVersion:        domain.UserIdentityCommandSchemaVersion,
		OperationID:          operationID,
		IdempotencyKey:       operationID,
		CommandType:          domain.UserIdentityCommandUpsert,
		UserID:               userInfo.ID,
		Name:                 st.Name,
		Email:                userInfo.Email,
		Sex:                  string(st.Sex),
		Grade:                grade,
		ClassNumber:          classNumber,
		StudentNumberInClass: studentNumberInClass,
		Major:                major,
		Role:                 string(st.Role),
		GithubID:             st.GitHubID,
		DataGSMStudentID:     &studentID,
		RequestedBy:          userInfo.ID,
		OccurredAt:           s.now().UTC().Truncate(time.Microsecond),
	}
	if err := domain.ValidateUserIdentityCommand(command); err != nil {
		return nil, ErrAuthenticationRejected
	}
	return s.commitIdentityAndIssueSession(
		ctx,
		command,
		userInfo.Email,
		platformRole,
		string(st.Role),
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

// PlatformRoleFromDataGSM does not grant Cowork administration from a provider account role.
func PlatformRoleFromDataGSM(providerRole string) (string, error) {
	switch providerRole {
	case "ADMIN", "USER":
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

func studentInteger(value *int) (*int8, error) {
	if value == nil {
		return nil, nil
	}
	if *value < 0 || *value > 127 {
		return nil, ErrAuthenticationRejected
	}
	result := int8(*value)
	return &result, nil
}
