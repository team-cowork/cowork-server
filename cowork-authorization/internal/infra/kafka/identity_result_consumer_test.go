package kafka

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/domain"
	"github.com/segmentio/kafka-go"
)

const identityResultOperationID = "00000000-0000-4000-8000-000000000001"

func validIdentityResultSuccessJSON() string {
	return `{
		"schemaVersion": 1,
		"operationId": "` + identityResultOperationID + `",
		"status": "SUCCEEDED",
		"userId": 7,
		"occurredAt": "2026-08-26T01:02:03Z"
	}`
}

func TestDecodeIdentityResultAcceptsWellFormedSuccessPayload(t *testing.T) {
	t.Parallel()

	result, err := decodeIdentityResult([]byte(validIdentityResultSuccessJSON()))
	if err != nil {
		t.Fatalf("decodeIdentityResult() error = %v", err)
	}
	if result.OperationID != identityResultOperationID {
		t.Fatalf("OperationID = %q, want %q", result.OperationID, identityResultOperationID)
	}
	if result.Status != domain.UserIdentitySucceeded {
		t.Fatalf("Status = %q, want %q", result.Status, domain.UserIdentitySucceeded)
	}
	if result.UserID == nil || *result.UserID != 7 {
		t.Fatalf("UserID = %v, want pointer to 7", result.UserID)
	}
	wantOccurredAt := time.Date(2026, time.August, 26, 1, 2, 3, 0, time.UTC)
	if !result.OccurredAt.Equal(wantOccurredAt) {
		t.Fatalf("OccurredAt = %s, want %s", result.OccurredAt, wantOccurredAt)
	}
}

func TestDecodeIdentityResultAcceptsWellFormedFailurePayload(t *testing.T) {
	t.Parallel()

	payload := `{
		"schemaVersion": 1,
		"operationId": "` + identityResultOperationID + `",
		"status": "FAILED",
		"error": {"code": "CONFLICT", "message": "email already in use"},
		"occurredAt": "2026-08-26T01:02:03Z"
	}`
	result, err := decodeIdentityResult([]byte(payload))
	if err != nil {
		t.Fatalf("decodeIdentityResult() error = %v", err)
	}
	if result.Status != domain.UserIdentityFailed {
		t.Fatalf("Status = %q, want %q", result.Status, domain.UserIdentityFailed)
	}
	if result.Error == nil || result.Error.Code != "CONFLICT" {
		t.Fatalf("Error = %+v, want CONFLICT", result.Error)
	}
	if result.UserID != nil {
		t.Fatalf("UserID = %v, want nil for FAILED result", result.UserID)
	}
}

func TestDecodeIdentityResultRejectsMalformedPayloads(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name    string
		payload string
	}{
		{
			name:    "not JSON",
			payload: `not json`,
		},
		{
			name:    "unknown field",
			payload: `{"schemaVersion":1,"operationId":"` + identityResultOperationID + `","status":"SUCCEEDED","userId":7,"occurredAt":"2026-08-26T01:02:03Z","unexpected":true}`,
		},
		{
			name:    "trailing JSON after object",
			payload: `{"schemaVersion":1,"operationId":"` + identityResultOperationID + `","status":"SUCCEEDED","userId":7,"occurredAt":"2026-08-26T01:02:03Z"}{}`,
		},
		{
			name:    "trailing garbage after object",
			payload: `{"schemaVersion":1,"operationId":"` + identityResultOperationID + `","status":"SUCCEEDED","userId":7,"occurredAt":"2026-08-26T01:02:03Z"} garbage`,
		},
		{
			name:    "empty payload",
			payload: ``,
		},
		{
			name:    "fails domain validation - bad schema version",
			payload: `{"schemaVersion":2,"operationId":"` + identityResultOperationID + `","status":"SUCCEEDED","userId":7,"occurredAt":"2026-08-26T01:02:03Z"}`,
		},
		{
			name:    "fails domain validation - SUCCEEDED with error set",
			payload: `{"schemaVersion":1,"operationId":"` + identityResultOperationID + `","status":"SUCCEEDED","userId":7,"error":{"code":"X","message":"y"},"occurredAt":"2026-08-26T01:02:03Z"}`,
		},
		{
			name:    "fails domain validation - invalid operationId",
			payload: `{"schemaVersion":1,"operationId":"not-a-uuid","status":"SUCCEEDED","userId":7,"occurredAt":"2026-08-26T01:02:03Z"}`,
		},
	}

	for _, testCase := range tests {
		testCase := testCase
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()
			if _, err := decodeIdentityResult([]byte(testCase.payload)); err == nil {
				t.Fatalf("decodeIdentityResult(%q) error = nil, want error", testCase.payload)
			}
		})
	}
}

func TestTruncateDLTReasonPassesShortReasonThrough(t *testing.T) {
	t.Parallel()

	reason := "result key does not match operationId"
	if got := truncateDLTReason(reason); got != reason {
		t.Fatalf("truncateDLTReason(%q) = %q, want unchanged", reason, got)
	}
}

func TestTruncateDLTReasonTruncatesAtMaxBytes(t *testing.T) {
	t.Parallel()

	const maxBytes = 500
	reason := strings.Repeat("a", maxBytes+50)
	got := truncateDLTReason(reason)
	if len(got) != maxBytes {
		t.Fatalf("len(truncateDLTReason(...)) = %d, want %d", len(got), maxBytes)
	}
	if got != reason[:maxBytes] {
		t.Fatalf("truncateDLTReason(...) did not keep the leading %d bytes", maxBytes)
	}
}

func TestTruncateDLTReasonKeepsReasonExactlyAtLimit(t *testing.T) {
	t.Parallel()

	const maxBytes = 500
	reason := strings.Repeat("b", maxBytes)
	if got := truncateDLTReason(reason); got != reason {
		t.Fatalf("truncateDLTReason(exact-limit reason) = %q, want unchanged", got)
	}
}

func TestNewIdentityResultConsumerWiresDependenciesAndTopics(t *testing.T) {
	t.Parallel()

	processor := &fakeIdentityResultProcessor{}
	publisher := &fakeIdentityResultDeadLetterPublisher{}
	consumer := NewIdentityResultConsumer(
		"broker-a:9092,broker-b:9092",
		"user.identity.result",
		"cowork-authorization-identity-result",
		"user.identity.result.dlt",
		processor,
		publisher,
	)
	t.Cleanup(func() { _ = consumer.Close() })

	if consumer.reader == nil {
		t.Fatal("reader = nil, want configured kafka.Reader")
	}
	if consumer.dltTopic != "user.identity.result.dlt" {
		t.Fatalf("dltTopic = %q, want user.identity.result.dlt", consumer.dltTopic)
	}
	if consumer.processor != processor {
		t.Fatal("processor was not wired to the constructor argument")
	}
	if consumer.publisher != publisher {
		t.Fatal("publisher was not wired to the constructor argument")
	}
}

type fakeIdentityResultProcessor struct{}

func (*fakeIdentityResultProcessor) ApplyResult(
	context.Context,
	string,
	domain.UserIdentityCommandResult,
) error {
	return nil
}

type fakeIdentityResultDeadLetterPublisher struct{}

func (*fakeIdentityResultDeadLetterPublisher) PublishToWithHeaders(
	context.Context,
	string,
	string,
	[]byte,
	[]kafka.Header,
) error {
	return nil
}
