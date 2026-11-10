package service

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"github.com/tmp-it-play/datagsm-sdk-go/event"
)

const testSecret = "unit-test-webhook-secret"
const studentJSON = `{"student_id":7,"name":"Student","email":"student@gsm.hs.kr","sex":"MAN","role":"GENERAL_STUDENT","student_number":null,"major":null,"specialty":null,"github_id":null}`

func sign(body []byte) string {
	mac := hmac.New(sha256.New, []byte(testSecret))
	mac.Write(body)
	return "sha256=" + hex.EncodeToString(mac.Sum(nil))
}

type fakeWebhookInbox struct {
	calls int
	batch domain.WebhookBatch
	err   error
}

func (f *fakeWebhookInbox) SubmitBatch(_ context.Context, batch domain.WebhookBatch, _ string) (domain.WebhookResult, error) {
	f.calls++
	f.batch = batch
	if f.err != nil {
		return "", f.err
	}
	return domain.WebhookAccepted, nil
}
func eventBody(old, new string) []byte {
	return []byte(`{"id":"event-1","event":"student.updated","timestamp":"2026-11-10T00:00:00Z","data":{"old":` + old + `,"new":` + new + `}}`)
}
func TestStudentEventAcceptance(t *testing.T) {
	for _, tc := range []struct {
		name, old, new string
		valid          bool
	}{
		{"creation", `[{"index":0,"object":{}}]`, `[{"index":0,"object":` + studentJSON + `}]`, true},
		{"update", `[{"index":0,"object":` + studentJSON + `}]`, `[{"index":0,"object":` + studentJSON + `}]`, true},
		{"graduate with null profile fields", `[{"index":0,"object":` + studentJSON + `}]`, `[{"index":0,"object":` + strings.Replace(studentJSON, "GENERAL_STUDENT", "GRADUATE", 1) + `}]`, true},
		{"future student roles are retained", `[{"index":0,"object":{}}]`, `[{"index":0,"object":` + strings.Replace(studentJSON, "GENERAL_STUDENT", "FUTURE_ROLE", 1) + `}]`, true},
		{"old snapshots are required", `null`, `[{"index":0,"object":` + studentJSON + `}]`, false},
		{"student deletion is invalid", `[{"index":0,"object":` + studentJSON + `}]`, `[{"index":0,"object":{}}]`, false},
		{"mismatched identities", `[{"index":0,"object":{"student_id":8}}]`, `[{"index":0,"object":` + studentJSON + `}]`, false},
		{"duplicate indices", `[{"index":0,"object":{}},{"index":0,"object":{}}]`, `[{"index":0,"object":` + studentJSON + `},{"index":0,"object":` + studentJSON + `}]`, false},
		{"duplicate students", `[{"index":0,"object":{}},{"index":1,"object":{}}]`, `[{"index":0,"object":` + studentJSON + `},{"index":1,"object":` + studentJSON + `}]`, false},
		{"business field validation", `[{"index":0,"object":{}}]`, `[{"index":0,"object":{"student_id":7}}]`, false},
		{"oversized name", `[{"index":0,"object":{}}]`, `[{"index":0,"object":` + strings.Replace(studentJSON, "Student", strings.Repeat("x", 51), 1) + `}]`, false},
		{"case variants", `[{"index":0,"object":{}}]`, `[{"index":0,"object":` + strings.Replace(studentJSON, "student_id", "STUDENT_ID", 1) + `}]`, false},
		{"duplicate JSON keys", `[{"index":0,"object":{}}]`, `[{"index":0,"object":` + strings.Replace(studentJSON, `"student_id":7`, `"student_id":7,"student_id":8`, 1) + `}]`, false},
		{"empty batch", `[]`, `[]`, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			inbox := &fakeWebhookInbox{}
			svc := NewEventService(&config.AppConfig{DataGSMWebhookSecret: testSecret, KafkaTopicUserSync: "user.data.sync"}, inbox)
			body := eventBody(tc.old, tc.new)
			result, err := svc.ProcessEvent(context.Background(), body, sign(body))
			if tc.valid {
				if err != nil || result != domain.WebhookAccepted || inbox.calls != 1 || len(inbox.batch.Messages) != 1 {
					t.Fatalf("result=%s err=%v batch=%+v", result, err, inbox.batch)
				}
				var message userSyncMessage
				if err = json.Unmarshal(inbox.batch.Messages[0].Payload, &message); err != nil {
					t.Fatal(err)
				}
				if message.DataGSMRefID != 7 || message.Major != nil || message.StudentNumber != nil {
					t.Fatalf("nullable fields lost: %+v", message)
				}
			} else if !errors.Is(err, ErrInvalidPayload) || inbox.calls != 0 {
				t.Fatalf("error=%v writes=%d", err, inbox.calls)
			}
		})
	}
}
func TestWebhookAuthenticationAndEnvelope(t *testing.T) {
	valid := eventBody(`[{"index":0,"object":{}}]`, `[{"index":0,"object":`+studentJSON+`}]`)
	for _, tc := range []struct {
		name      string
		body      []byte
		signature string
		want      error
	}{
		{"missing signature", valid, "", event.ErrInvalidSignature},
		{"wrong signature", valid, "sha256=" + strings.Repeat("0", 64), event.ErrInvalidSignature},
		{"invalid timestamp", []byte(strings.Replace(string(valid), "2026-11-10T00:00:00Z", "invalid", 1)), "signed", ErrInvalidPayload},
		{"blank event identity", []byte(strings.Replace(string(valid), "event-1", " ", 1)), "signed", ErrInvalidPayload},
		{"duplicate event identity", []byte(strings.Replace(string(valid), `"id":"event-1"`, `"id":"event-1","id":"other"`, 1)), "signed", ErrInvalidPayload},
		{"invalid UTF8", []byte(strings.Replace(string(valid), "Student", string([]byte{255}), 1)), "signed", ErrInvalidPayload},
	} {
		t.Run(tc.name, func(t *testing.T) {
			inbox := &fakeWebhookInbox{}
			svc := NewEventService(&config.AppConfig{DataGSMWebhookSecret: testSecret}, inbox)
			signature := tc.signature
			if signature == "signed" {
				signature = sign(tc.body)
			}
			_, err := svc.ProcessEvent(context.Background(), tc.body, signature)
			if !errors.Is(err, tc.want) || inbox.calls != 0 {
				t.Fatalf("err=%v writes=%d", err, inbox.calls)
			}
		})
	}
}

func TestUnsubscribedEventsAreIgnoredAfterAuthentication(t *testing.T) {
	for _, kind := range []string{"club.updated", "project.updated", "student.created", "ping"} {
		t.Run(kind, func(t *testing.T) {
			inbox := &fakeWebhookInbox{}
			svc := NewEventService(&config.AppConfig{DataGSMWebhookSecret: testSecret}, inbox)
			body := []byte(`{"id":"ignored-event","event":"` + kind + `","timestamp":"2026-11-10T00:00:00Z","data":{"future_field":true}}`)
			result, err := svc.ProcessEvent(context.Background(), body, sign(body))
			if err != nil || result != domain.WebhookIgnored || inbox.calls != 0 {
				t.Fatalf("result=%s err=%v writes=%d", result, err, inbox.calls)
			}
			if _, err = svc.ProcessEvent(context.Background(), body, "invalid"); !errors.Is(err, event.ErrInvalidSignature) {
				t.Fatalf("ignored event bypassed signature verification: %v", err)
			}
		})
	}
}
func TestWebhookHashPreservesStoredReceiptContract(t *testing.T) {
	inbox := &fakeWebhookInbox{}
	svc := NewEventService(&config.AppConfig{DataGSMWebhookSecret: testSecret}, inbox)
	body := eventBody(`[{"index":0,"object":{}}]`, `[{"index":0,"object":`+studentJSON+`}]`)
	if _, err := svc.ProcessEvent(context.Background(), body, sign(body)); err != nil {
		t.Fatal(err)
	}
	canonical := `{"event":"student.updated","timestamp":"2026-11-10T00:00:00Z","items":[{"index":0,"object":` + studentJSON + `}]}`
	if inbox.batch.PayloadHash != sha256.Sum256([]byte(canonical)) {
		t.Fatal("SDK migration changed the persisted canonical hash")
	}
	storageErr := errors.New("unavailable")
	inbox.err = storageErr
	if _, err := svc.ProcessEvent(context.Background(), body, sign(body)); !errors.Is(err, storageErr) {
		t.Fatal("storage errors must remain retryable")
	}
}
