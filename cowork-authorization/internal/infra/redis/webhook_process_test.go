package redisinfra

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/cowork/authorization/internal/domain"
	redis "github.com/redis/go-redis/v9"
)

type fakeWebhookProcessor struct {
	signatureValid bool
	result         domain.WebhookResult
	processErr     error
	verifyCalls    int
	processCalls   int
}

func (f *fakeWebhookProcessor) VerifySignature(_ []byte, _ string) bool {
	f.verifyCalls++
	return f.signatureValid
}

func (f *fakeWebhookProcessor) ProcessEvent(_ context.Context, _ []byte) (domain.WebhookResult, error) {
	f.processCalls++
	return f.result, f.processErr
}

func TestProcessQueuedWebhook(t *testing.T) {
	validValues := map[string]any{"body": `{"id":"event-1"}`, "signature": "sig"}
	processingErr := errors.New("database down")
	ackErr := errors.New("redis down")

	tests := []struct {
		name             string
		values           map[string]any
		signatureValid   bool
		processErr       error
		ackErr           error
		wantResult       domain.WebhookResult
		wantErr          error
		wantKind         string
		wantVerifyCalls  int
		wantProcessCalls int
		wantAckCalls     int
	}{
		{
			name:             "처리에 성공하면 ack하고 결과를 반환한다",
			values:           validValues,
			signatureValid:   true,
			wantResult:       domain.WebhookAccepted,
			wantVerifyCalls:  1,
			wantProcessCalls: 1,
			wantAckCalls:     1,
		},
		{
			name:             "처리 오류면 ack하지 않고 pending으로 남긴다",
			values:           validValues,
			signatureValid:   true,
			processErr:       processingErr,
			wantErr:          processingErr,
			wantKind:         "processing",
			wantVerifyCalls:  1,
			wantProcessCalls: 1,
		},
		{
			name:             "ack 오류면 오류를 반환한다",
			values:           validValues,
			signatureValid:   true,
			ackErr:           ackErr,
			wantErr:          ackErr,
			wantKind:         "processing",
			wantVerifyCalls:  1,
			wantProcessCalls: 1,
			wantAckCalls:     1,
		},
		{
			name:             "중복 이벤트 충돌이면 invalid로 분류해 삭제 대상이 된다",
			values:           validValues,
			signatureValid:   true,
			processErr:       domain.ErrWebhookConflict,
			wantErr:          domain.ErrWebhookConflict,
			wantKind:         "invalid",
			wantVerifyCalls:  1,
			wantProcessCalls: 1,
		},
		{
			name:             "만료된 이벤트면 invalid로 분류해 삭제 대상이 된다",
			values:           validValues,
			signatureValid:   true,
			processErr:       domain.ErrWebhookExpired,
			wantErr:          domain.ErrWebhookExpired,
			wantKind:         "invalid",
			wantVerifyCalls:  1,
			wantProcessCalls: 1,
		},
		{
			name:            "서명이 맞지 않으면 처리하지 않고 invalid로 분류한다",
			values:          validValues,
			wantErr:         domain.ErrInvalidWebhookPayload,
			wantVerifyCalls: 1,
			wantKind:        "invalid",
		},
		{
			name:           "body가 없으면 invalid로 분류한다",
			values:         map[string]any{"signature": "sig"},
			signatureValid: true,
			wantErr:        domain.ErrInvalidWebhookPayload,
			wantKind:       "invalid",
		},
		{
			name:           "signature가 없으면 invalid로 분류한다",
			values:         map[string]any{"body": `{"id":"event-1"}`},
			signatureValid: true,
			wantErr:        domain.ErrInvalidWebhookPayload,
			wantKind:       "invalid",
		},
		{
			name:           "body가 비어 있으면 invalid로 분류한다",
			values:         map[string]any{"body": "", "signature": "sig"},
			signatureValid: true,
			wantErr:        domain.ErrInvalidWebhookPayload,
			wantKind:       "invalid",
		},
		{
			name:           "body가 최대 크기를 넘으면 invalid로 분류한다",
			values:         map[string]any{"body": strings.Repeat("a", maxWebhookBodyBytes+1), "signature": "sig"},
			signatureValid: true,
			wantErr:        domain.ErrInvalidWebhookPayload,
			wantKind:       "invalid",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			processor := &fakeWebhookProcessor{
				signatureValid: tt.signatureValid,
				result:         domain.WebhookAccepted,
				processErr:     tt.processErr,
			}
			var ackedIDs []string
			ack := func(_ context.Context, id string) error {
				ackedIDs = append(ackedIDs, id)
				return tt.ackErr
			}
			message := redis.XMessage{ID: "1700000000000-0", Values: tt.values}

			result, err := processQueuedWebhook(context.Background(), processor, message, ack)

			if !errors.Is(err, tt.wantErr) || (tt.wantErr == nil && err != nil) {
				t.Fatalf("processQueuedWebhook() error = %v, want %v", err, tt.wantErr)
			}
			if result != tt.wantResult {
				t.Fatalf("processQueuedWebhook() result = %q, want %q", result, tt.wantResult)
			}
			if processor.verifyCalls != tt.wantVerifyCalls {
				t.Fatalf("VerifySignature calls = %d, want %d", processor.verifyCalls, tt.wantVerifyCalls)
			}
			if processor.processCalls != tt.wantProcessCalls {
				t.Fatalf("ProcessEvent calls = %d, want %d", processor.processCalls, tt.wantProcessCalls)
			}
			if len(ackedIDs) != tt.wantAckCalls {
				t.Fatalf("ack calls = %d, want %d", len(ackedIDs), tt.wantAckCalls)
			}
			for _, id := range ackedIDs {
				if id != message.ID {
					t.Fatalf("acked id = %q, want %q", id, message.ID)
				}
			}
			if err != nil {
				if kind := streamErrorKind(err); kind != tt.wantKind {
					t.Fatalf("streamErrorKind() = %q, want %q", kind, tt.wantKind)
				}
			}
		})
	}
}
