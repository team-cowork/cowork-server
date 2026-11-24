package service

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"time"

	"github.com/tmp-it-play/datagsm-sdk-go/oauth"
	"gorm.io/gorm"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
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
	EnsureUser(context.Context, domain.UserIdentityCommand) (*domain.SessionIdentity, error)
}

type AuthService struct {
	cfg              *config.AppConfig
	login            *DataGSMLogin
	github           *GitHubLogin
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
	github *GitHubLogin,
) *AuthService {
	return &AuthService{
		cfg:              cfg,
		login:            login,
		github:           github,
		identity:         identity,
		refreshTokenRepo: refreshTokenRepo,
		tokenSvc:         tokenSvc,
		now: func() time.Time {
			return time.Now().UTC().Truncate(time.Microsecond)
		},
	}
}

// BeginLogin starts a login with DataGSM, the default provider, or with GitHub when it is configured.
func (s *AuthService) BeginLogin(ctx context.Context, provider, redirectURI string) (string, string, error) {
	switch provider {
	case "", ProviderDataGSM:
		return s.login.Begin(ctx, redirectURI)
	case ProviderGitHub:
		if s.github != nil {
			return s.github.Begin(ctx, redirectURI, 0)
		}
	}
	return "", "", loginFailure("provider", ErrInvalidLoginRequest)
}

// BeginLink starts authentication of a second login method for the signed-in user. Only GitHub can be linked.
func (s *AuthService) BeginLink(ctx context.Context, userID int64, provider, redirectURI string) (string, string, error) {
	if userID <= 0 || provider != ProviderGitHub || s.github == nil {
		return "", "", loginFailure("provider", ErrInvalidLoginRequest)
	}
	return s.github.Begin(ctx, redirectURI, userID)
}

func (s *AuthService) AllowsLoginOrigin(origin string) bool {
	return s.login.AllowsOrigin(origin) || s.github != nil && s.github.AllowsOrigin(origin)
}

func (s *AuthService) CompleteLogin(ctx context.Context, session string, callback url.Values) (*TokenPair, error) {
	attempt, err := s.login.take(ctx, session)
	if err != nil {
		return nil, err
	}
	// A link attempt must not sign in, because its result is bound to the user who started it.
	if attempt.LinkUserID != 0 {
		return nil, loginFailure("attempt_purpose", ErrAuthenticationRejected)
	}
	if attempt.provider() == ProviderGitHub {
		identity, err := s.completeGitHub(ctx, attempt, callback)
		if err != nil {
			return nil, err
		}
		command, err := s.githubCommand(domain.UserIdentityCommandUpsert, identity, nil)
		if err != nil {
			return nil, err
		}
		return s.commitIdentityAndIssueSession(ctx, command)
	}
	userInfo, err := s.login.CompleteAttempt(ctx, attempt, callback)
	if err != nil {
		return nil, err
	}
	return s.issueUserSession(ctx, userInfo)
}

// CompleteLink adds the authenticated GitHub account to the user who started the link attempt.
// It does not issue a new session, and it does not change the account profile.
func (s *AuthService) CompleteLink(ctx context.Context, userID int64, session string, callback url.Values) error {
	attempt, err := s.login.take(ctx, session)
	if err != nil {
		return err
	}
	if userID <= 0 || attempt.LinkUserID != userID || attempt.Provider != ProviderGitHub {
		return loginFailure("attempt_purpose", ErrAuthenticationRejected)
	}
	identity, err := s.completeGitHub(ctx, attempt, callback)
	if err != nil {
		return err
	}
	command, err := s.githubCommand(domain.UserIdentityCommandLink, identity, &userID)
	if err != nil {
		return err
	}
	committed, err := s.identity.EnsureUser(ctx, command)
	if err != nil {
		return fmt.Errorf("failed to link user identity: %w", err)
	}
	if committed.UserID != userID {
		return fmt.Errorf("%w: linked identity belongs to another account", ErrAuthenticationRejected)
	}
	return nil
}

func (s *AuthService) completeGitHub(ctx context.Context, attempt LoginAttempt, callback url.Values) (*GitHubIdentity, error) {
	if s.github == nil {
		return nil, loginFailure("provider", ErrAuthenticationRejected)
	}
	return s.github.CompleteAttempt(ctx, attempt, callback)
}

func (s *AuthService) githubCommand(commandType string, identity *GitHubIdentity, accountID *int64) (domain.UserIdentityCommand, error) {
	command, err := s.identityCommand(ProviderGitHub, strconv.FormatInt(identity.ID, 10), identity.Name, identity.Email, nil)
	if err != nil {
		return command, err
	}
	login := identity.Login
	gsm := identity.GSMEmailVerified
	command.CommandType, command.GithubLogin, command.GSMEmailVerified, command.AccountID = commandType, &login, &gsm, accountID
	if domain.ValidateUserIdentityCommand(command) != nil {
		return command, ErrAuthenticationRejected
	}
	return command, nil
}

func (s *AuthService) issueUserSession(ctx context.Context, userInfo *oauth.UserInfo) (*TokenPair, error) {
	if userInfo.ObjectType == nil || *userInfo.ObjectType != "STUDENT" || userInfo.Student == nil {
		return nil, fmt.Errorf("non-student users are not supported")
	}

	st := userInfo.Student
	if err := validateDataGSMAccountRole(userInfo.Role); err != nil {
		return nil, err
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
	student := &domain.StudentIdentity{
		Sex: string(st.Sex), Grade: grade, ClassNumber: classNumber, StudentNumberInClass: studentNumberInClass,
		Major: major, Role: string(st.Role), GithubID: st.GitHubID, DataGSMStudentID: &studentID,
	}
	return s.loginWithIdentity(ctx, "DATAGSM", strconv.FormatInt(userInfo.ID, 10), st.Name, userInfo.Email, student)
}

func (s *AuthService) loginWithIdentity(ctx context.Context, provider, subject, name, email string, student *domain.StudentIdentity) (*TokenPair, error) {
	command, err := s.identityCommand(provider, subject, name, email, student)
	if err != nil {
		return nil, err
	}
	if err := domain.ValidateUserIdentityCommand(command); err != nil {
		return nil, ErrAuthenticationRejected
	}
	return s.commitIdentityAndIssueSession(ctx, command)
}

func (s *AuthService) identityCommand(provider, subject, name, email string, student *domain.StudentIdentity) (domain.UserIdentityCommand, error) {
	operationID, err := domain.NewUUID()
	if err != nil {
		return domain.UserIdentityCommand{}, fmt.Errorf("%w: generate login operation", ErrAuthenticationUnavailable)
	}
	return domain.UserIdentityCommand{
		SchemaVersion: domain.UserIdentityCommandSchemaVersion, OperationID: operationID, IdempotencyKey: operationID,
		CommandType: domain.UserIdentityCommandUpsert, Provider: provider, ProviderSubject: subject, Name: name, Email: email,
		Student: student, OccurredAt: s.now().UTC().Truncate(time.Microsecond),
	}, nil
}

func (s *AuthService) DevSignIn(ctx context.Context, id, password string) (*TokenPair, error) {
	slot, ok := s.cfg.DevAccounts.Authenticate(id, password)
	if !ok {
		return nil, ErrAuthenticationRejected
	}
	return s.loginWithIdentity(ctx, "DEV", slot, "개발 계정 "+slot[4:], slot+"@dev.cowork.invalid", nil)
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
	gsmRole string,
	deviceInfo string,
) (*TokenPair, error) {
	accessToken, err := s.tokenSvc.GenerateAccessToken(userID, email, gsmRole)
	if err != nil {
		return nil, fmt.Errorf("failed to generate access token: %w", err)
	}

	rawRefresh, refreshHash, err := s.tokenSvc.GenerateRefreshToken()
	if err != nil {
		return nil, fmt.Errorf("failed to generate refresh token: %w", err)
	}

	occurredAt := s.now().UTC().Truncate(time.Microsecond)
	rt := &domain.RefreshToken{
		UserID:    userID,
		TokenHash: refreshHash,
		Email:     email,
		GsmRole:   gsmRole,
		ExpiresAt: occurredAt.Add(s.tokenSvc.RefreshExpire()),
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
) (*TokenPair, error) {
	identity, err := s.identity.EnsureUser(ctx, command)
	if err != nil {
		return nil, fmt.Errorf("failed to commit user identity: %w", err)
	}
	pair, err := s.issueNewSession(ctx, identity.UserID, identity.Email, identity.GsmRole, "")
	if err != nil {
		return nil, fmt.Errorf("%w: failed to issue session: %v", ErrAuthenticationUnavailable, err)
	}
	return pair, nil
}

func validateDataGSMAccountRole(providerRole string) error {
	switch providerRole {
	case "ADMIN", "USER":
		return nil
	default:
		return fmt.Errorf("unsupported DataGSM account role")
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
