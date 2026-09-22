package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/cowork/authorization/internal/config"
)

const testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func newSignatureService(secret string) *EventService {
	return NewEventService(&config.AppConfig{DataGSMWebhookSecret: secret}, nil)
}

func sign(body []byte) string {
	mac := hmac.New(sha256.New, []byte(testSecret))
	mac.Write(body)
	return "sha256=" + hex.EncodeToString(mac.Sum(nil))
}

func TestVerifySignature_AccordingToWebhookAuthenticity(t *testing.T) {
	t.Parallel()

	body := []byte(`{"hello":"world"}`)
	tests := []struct {
		name      string
		secret    string
		signature string
		want      bool
	}{
		{name: "a matching HMAC is accepted", secret: testSecret, signature: sign(body), want: true},
		{name: "a mismatched HMAC is rejected", secret: testSecret, signature: "sha256=" + hex.EncodeToString([]byte("nope"))},
		{name: "a signature without the sha256 prefix is rejected", secret: testSecret, signature: hex.EncodeToString([]byte("abc"))},
		{name: "an empty signature is rejected", secret: testSecret},
		{name: "verification fails closed when no secret is configured", signature: sign(body)},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			if got := newSignatureService(test.secret).VerifySignature(body, test.signature); got != test.want {
				t.Fatalf("VerifySignature() = %v, want %v", got, test.want)
			}
		})
	}
}

func TestBuildUserSyncMessages_AccordingToStudentPayload(t *testing.T) {
	t.Parallel()

	t.Run("a valid student update maps the account fields", func(t *testing.T) {
		t.Parallel()
		envelope := WebhookEvent{
			ID:        "event-1",
			Event:     "student.updated",
			Timestamp: "2026-08-26T01:02:03.123456Z",
			Data: json.RawMessage(`{
				"new":[{"index":2,"object":{
					"student_id":7,"name":"홍길동","email":"student@example.com",
					"sex":"MAN","role":"STUDENT_COUNCIL","student_number":2105,
					"major":"SW_DEVELOPMENT","github_id":"hong"
				}}]
			}`),
		}

		messages, err := validatedStudentMessages(envelope)
		if err != nil || len(messages) != 1 {
			t.Fatalf("buildUserSyncMessages() = %+v, %v", messages, err)
		}
		message := messages[0]
		if message.DataGSMRefID != 7 || message.StudentRole != "STUDENT_COUNCIL" || message.EventIndex != 2 {
			t.Fatalf("mapped message = %+v", message)
		}
		if message.StudentNumber == nil || *message.StudentNumber != 2105 || message.GithubID == nil || *message.GithubID != "hong" {
			t.Fatalf("optional account fields = %+v", message)
		}
	})

	tests := []struct {
		name string
		data string
	}{
		{name: "missing new account data is rejected", data: `{"new":[]}`},
		{name: "a non-positive student id is rejected", data: `{"new":[{"index":0,"object":{"student_id":0,"name":"홍길동","email":"x@example.com","sex":"MAN","role":"STUDENT"}}]}`},
		{name: "missing identity fields are rejected", data: `{"new":[{"index":0,"object":{"student_id":7,"email":"x@example.com","sex":"MAN","role":"STUDENT"}}]}`},
		{name: "missing student role is rejected", data: `{"new":[{"index":0,"object":{"student_id":7,"name":"홍길동","email":"x@example.com","sex":"MAN"}}]}`},
		{name: "blank names are rejected before acceptance", data: `{"new":[{"index":0,"object":{"student_id":7,"name":"  ","email":"x@example.com","sex":"MAN","role":"STUDENT"}}]}`},
		{name: "a missing item index is not index zero", data: `{"new":[{"object":{"student_id":7,"name":"홍길동","email":"x@example.com","sex":"MAN","role":"STUDENT"}}]}`},
		{name: "negative item indices are rejected", data: `{"new":[{"index":-1,"object":{"student_id":7,"name":"홍길동","email":"x@example.com","sex":"MAN","role":"STUDENT"}}]}`},
		{name: "null student objects are not no-ops", data: `{"new":[{"index":0,"object":null}]}`},
		{name: "duplicate indices are rejected even for no-ops", data: `{"new":[{"index":0,"object":{}},{"index":0,"object":{}}]}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			messages, err := validatedStudentMessages(WebhookEvent{
				ID: "event-1", Event: "student.updated", Timestamp: "2026-08-26T01:02:03Z", Data: json.RawMessage(test.data),
			})
			if !errors.Is(err, ErrInvalidPayload) || messages != nil {
				t.Fatalf("buildUserSyncMessages() = %+v, %v; want invalid payload", messages, err)
			}
		})
	}
}

// Exercise the same input policy and mapping functions used by ProcessEvent.
func validatedStudentMessages(envelope WebhookEvent) ([]userSyncMessage, error) {
	items, err := parseStudentItems(envelope.Data)
	if err != nil {
		return nil, err
	}
	return studentMessages(envelope, items), nil
}

func TestStudentBatchPolicy(t *testing.T) {
	t.Parallel()
	student := `{"student_id":7,"name":"홍길동","email":"student@example.com","sex":"MAN","role":"STUDENT"}`
	for _, test := range []struct {
		name, data   string
		wantMessages int
		invalid      bool
	}{
		{"empty objects are explicit no-ops", `{"new":[{"index":0,"object":{}}]}`, 0, false},
		{"no-op and student change can coexist", `{"new":[{"index":0,"object":{}},{"index":1,"object":` + student + `}]}`, 1, false},
		{"one student cannot change twice in a batch", `{"new":[{"index":0,"object":` + student + `},{"index":1,"object":` + student + `}]}`, 0, true},
		{"one invalid item rejects the complete batch", `{"new":[{"index":0,"object":` + student + `},{"index":1,"object":{"student_id":8}}]}`, 0, true},
		{"oversized names cannot reach the consumer", `{"new":[{"index":0,"object":` + strings.Replace(student, "홍길동", strings.Repeat("가", 51), 1) + `}]}`, 0, true},
		{"duplicate business fields are ambiguous", `{"new":[{"index":0,"object":` + strings.Replace(student, `"student_id":7`, `"student_id":7,"student_id":8`, 1) + `}]}`, 0, true},
		{"student IDs must fit the owner's signed integer", `{"new":[{"index":0,"object":` + strings.Replace(student, `"student_id":7`, `"student_id":9223372036854775808`, 1) + `}]}`, 0, true},
		{"case variants cannot impersonate provider fields", `{"new":[{"index":0,"object":` + strings.Replace(student, `"student_id"`, `"STUDENT_ID"`, 1) + `}]}`, 0, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			messages, err := validatedStudentMessages(WebhookEvent{Data: json.RawMessage(test.data)})
			if test.invalid {
				if !errors.Is(err, ErrInvalidPayload) || messages != nil {
					t.Fatalf("accepted invalid batch: %v", err)
				}
				return
			}
			if err != nil || len(messages) != test.wantMessages {
				t.Fatalf("messages = %d, error = %v", len(messages), err)
			}
		})
	}
}

func TestWebhookEnvelopePolicy(t *testing.T) {
	t.Parallel()
	valid := `{"id":"event-1","event":"student.updated","timestamp":"2026-09-22T01:02:03Z","data":{"new":[]}}`
	for _, test := range []struct {
		name, body string
		valid      bool
	}{
		{"well-formed envelope", valid, true},
		{"empty identity", strings.Replace(valid, `"event-1"`, `""`, 1), false},
		{"identity with trailing whitespace", strings.Replace(valid, `"event-1"`, `"event-1 "`, 1), false},
		{"identity beyond storage byte limit", strings.Replace(valid, "event-1", strings.Repeat("가", 86), 1), false},
		{"duplicate event IDs", strings.Replace(valid, `"id":"event-1"`, `"id":"event-1","id":"event-2"`, 1), false},
		{"malformed timestamp", strings.Replace(valid, "2026-09-22T01:02:03Z", "not-a-time", 1), false},
		{"multiple JSON documents", valid + `{}`, false},
		{"invalid UTF-8", strings.Replace(valid, "event-1", string([]byte{255}), 1), false},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			_, _, err := parseWebhookEnvelope([]byte(test.body))
			if (err == nil) != test.valid {
				t.Fatalf("envelope error = %v", err)
			}
		})
	}
}
