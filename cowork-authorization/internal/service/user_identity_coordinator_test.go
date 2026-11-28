package service

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"gorm.io/gorm"

	"github.com/cowork/authorization/internal/domain"
)

type findResult struct {
	operation *domain.UserIdentityOperation
	err       error
}

type fakeUserIdentityOperationStore struct {
	submitID    string
	submitErr   error
	submitTopic string
	findResults []findResult
	findCalls   int
}

func (f *fakeUserIdentityOperationStore) Submit(
	_ context.Context,
	_ domain.UserIdentityCommand,
	topic string,
) (string, error) {
	f.submitTopic = topic
	return f.submitID, f.submitErr
}

func (f *fakeUserIdentityOperationStore) Find(
	_ context.Context,
	_ string,
) (*domain.UserIdentityOperation, error) {
	index := min(f.findCalls, len(f.findResults)-1)
	f.findCalls++
	result := f.findResults[index]
	return result.operation, result.err
}

func identityOperation(status string, resultUserID *int64, errorCode *string) *domain.UserIdentityOperation {
	return &domain.UserIdentityOperation{
		OperationID:  "operation-1",
		Status:       status,
		ResultUserID: resultUserID,
		ResultEmail:  stringPointer("cowork@example.com"), ResultGsmRole: stringPointer(""),
		ErrorCode: errorCode,
	}
}

func int64Pointer(value int64) *int64 {
	return &value
}

func stringPointer(value string) *string {
	return &value
}

func TestUserIdentityCoordinatorEnsureUser(t *testing.T) {
	const requestedUserID int64 = 42
	pending := findResult{operation: identityOperation(domain.UserIdentityPending, nil, nil)}

	tests := []struct {
		name            string
		submitErr       error
		findResults     []findResult
		waitTimeout     time.Duration
		wantUserID      int64
		wantUnavailable bool
		wantErrContains string
		wantMinFinds    int
		wantErr         error
	}{
		{
			name:         "pending 이후 succeeded가 되면 사용자 ID를 반환한다",
			findResults:  []findResult{pending, {operation: identityOperation(domain.UserIdentitySucceeded, int64Pointer(requestedUserID), nil)}},
			waitTimeout:  time.Second,
			wantUserID:   requestedUserID,
			wantMinFinds: 2,
		},
		{
			name:         "아직 기록이 없으면 다시 조회한다",
			findResults:  []findResult{{err: gorm.ErrRecordNotFound}, {operation: identityOperation(domain.UserIdentitySucceeded, int64Pointer(requestedUserID), nil)}},
			waitTimeout:  time.Second,
			wantUserID:   requestedUserID,
			wantMinFinds: 2,
		},
		{
			name:        "소유자가 발급한 사용자 ID를 반환한다",
			findResults: []findResult{{operation: identityOperation(domain.UserIdentitySucceeded, int64Pointer(requestedUserID+1), nil)}},
			waitTimeout: time.Second,
			wantUserID:  requestedUserID + 1,
		},
		{
			name:            "succeeded지만 사용자 ID가 없으면 거부한다",
			findResults:     []findResult{{operation: identityOperation(domain.UserIdentitySucceeded, nil, nil)}},
			waitTimeout:     time.Second,
			wantErrContains: "did not prove the requested owner commit",
		},
		{
			name:            "failed면 오류 코드를 담아 거부한다",
			findResults:     []findResult{{operation: identityOperation(domain.UserIdentityFailed, nil, stringPointer("USER_CONFLICT"))}},
			waitTimeout:     time.Second,
			wantErrContains: "user identity command failed: USER_CONFLICT",
		},
		{
			name:            "기존 계정과 연결되지 않은 로그인 수단이면 미연결 오류로 거부한다",
			findResults:     []findResult{{operation: identityOperation(domain.UserIdentityFailed, nil, stringPointer("IDENTITY_NOT_LINKED"))}},
			waitTimeout:     time.Second,
			wantErrContains: "user identity command failed: IDENTITY_NOT_LINKED",
			wantErr:         ErrIdentityNotLinked,
		},
		{
			name:            "다른 계정에 연결된 로그인 수단이면 연결 충돌로 거부한다",
			findResults:     []findResult{{operation: identityOperation(domain.UserIdentityFailed, nil, stringPointer("IDENTITY_ALREADY_LINKED"))}},
			waitTimeout:     time.Second,
			wantErrContains: "user identity command failed: IDENTITY_ALREADY_LINKED",
			wantErr:         ErrIdentityAlreadyLinked,
		},
		{
			name:            "학교 이메일 로그인에 필요한 DataGSM 인증을 호출자에게 알린다",
			findResults:     []findResult{{operation: identityOperation(domain.UserIdentityFailed, nil, stringPointer("DATAGSM_LOGIN_REQUIRED"))}},
			waitTimeout:     time.Second,
			wantErrContains: "user identity command failed: DATAGSM_LOGIN_REQUIRED",
			wantErr:         ErrDataGSMLoginRequired,
		},
		{
			name:            "failed에 오류 코드가 없으면 기본 코드로 거부한다",
			findResults:     []findResult{{operation: identityOperation(domain.UserIdentityFailed, nil, nil)}},
			waitTimeout:     time.Second,
			wantErrContains: "user identity command failed: USER_IDENTITY_FAILED",
		},
		{
			name:            "알 수 없는 상태면 인증 불가로 거부한다",
			findResults:     []findResult{{operation: identityOperation("UNKNOWN", nil, nil)}},
			waitTimeout:     time.Second,
			wantUnavailable: true,
			wantErrContains: "invalid user identity operation status",
		},
		{
			name:            "pending이 기한을 넘기면 인증 불가로 거부한다",
			findResults:     []findResult{pending},
			waitTimeout:     20 * time.Millisecond,
			wantUnavailable: true,
			wantErrContains: "did not complete before deadline",
		},
		{
			name:            "조회 오류면 인증 불가로 거부한다",
			findResults:     []findResult{{err: errors.New("database down")}},
			waitTimeout:     time.Second,
			wantUnavailable: true,
			wantErrContains: "failed to read user identity operation",
		},
		{
			name:            "명령 제출 오류면 인증 불가로 거부한다",
			submitErr:       errors.New("outbox down"),
			findResults:     []findResult{pending},
			waitTimeout:     time.Second,
			wantUnavailable: true,
			wantErrContains: "failed to submit user identity command",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			store := &fakeUserIdentityOperationStore{
				submitID:    "operation-1",
				submitErr:   tt.submitErr,
				findResults: tt.findResults,
			}
			coordinator := NewUserIdentityCoordinator(store, "user-identity-command", tt.waitTimeout)
			coordinator.pollInterval = time.Millisecond

			userID, err := coordinator.EnsureUser(context.Background(), domain.UserIdentityCommand{ProviderSubject: "24"})

			if store.submitTopic != "user-identity-command" {
				t.Fatalf("submit topic = %q, want user-identity-command", store.submitTopic)
			}
			if tt.wantErrContains == "" {
				if err != nil {
					t.Fatalf("EnsureUser() error = %v", err)
				}
				if userID == nil || userID.UserID != tt.wantUserID || userID.Email != "cowork@example.com" {
					t.Fatalf("EnsureUser() userID = %v, want %d", userID, tt.wantUserID)
				}
				if store.findCalls < tt.wantMinFinds {
					t.Fatalf("Find calls = %d, want at least %d", store.findCalls, tt.wantMinFinds)
				}
				return
			}
			if err == nil {
				t.Fatalf("EnsureUser() error = nil, want error containing %q", tt.wantErrContains)
			}
			if userID != nil {
				t.Fatalf("EnsureUser() userID = %v, want 0 on error", userID)
			}
			if !strings.Contains(err.Error(), tt.wantErrContains) {
				t.Fatalf("EnsureUser() error = %v, want containing %q", err, tt.wantErrContains)
			}
			if tt.wantErr != nil && !errors.Is(err, tt.wantErr) {
				t.Fatalf("EnsureUser() error = %v, want %v", err, tt.wantErr)
			}
			if got := errors.Is(err, ErrAuthenticationUnavailable); got != tt.wantUnavailable {
				t.Fatalf("errors.Is(err, ErrAuthenticationUnavailable) = %v, want %v", got, tt.wantUnavailable)
			}
			if tt.submitErr != nil && store.findCalls != 0 {
				t.Fatalf("Find calls = %d, want 0 after submit failure", store.findCalls)
			}
		})
	}
}
