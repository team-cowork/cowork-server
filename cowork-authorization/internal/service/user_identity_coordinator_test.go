package service

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/domain"
	"gorm.io/gorm"
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
		ErrorCode:    errorCode,
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
			name:            "succeeded지만 사용자 ID가 다르면 거부한다",
			findResults:     []findResult{{operation: identityOperation(domain.UserIdentitySucceeded, int64Pointer(requestedUserID+1), nil)}},
			waitTimeout:     time.Second,
			wantErrContains: "did not prove the requested owner commit",
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

			userID, err := coordinator.EnsureUser(context.Background(), domain.UserIdentityCommand{UserID: requestedUserID})

			if store.submitTopic != "user-identity-command" {
				t.Fatalf("submit topic = %q, want user-identity-command", store.submitTopic)
			}
			if tt.wantErrContains == "" {
				if err != nil {
					t.Fatalf("EnsureUser() error = %v", err)
				}
				if userID != tt.wantUserID {
					t.Fatalf("EnsureUser() userID = %d, want %d", userID, tt.wantUserID)
				}
				if store.findCalls < tt.wantMinFinds {
					t.Fatalf("Find calls = %d, want at least %d", store.findCalls, tt.wantMinFinds)
				}
				return
			}
			if err == nil {
				t.Fatalf("EnsureUser() error = nil, want error containing %q", tt.wantErrContains)
			}
			if userID != 0 {
				t.Fatalf("EnsureUser() userID = %d, want 0 on error", userID)
			}
			if !strings.Contains(err.Error(), tt.wantErrContains) {
				t.Fatalf("EnsureUser() error = %v, want containing %q", err, tt.wantErrContains)
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
