package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"testing"

	"github.com/cowork/authorization/internal/config"
)

const testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func newSignatureService(secret string) *EventService {
	return NewEventService(&config.AppConfig{DataGSMWebhookSecret: secret}, nil, nil)
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
		service := newSignatureService(testSecret)
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

		messages, err := service.buildUserSyncMessages(envelope)
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
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			messages, err := newSignatureService(testSecret).buildUserSyncMessages(WebhookEvent{
				ID: "event-1", Event: "student.updated", Timestamp: "2026-08-26T01:02:03Z", Data: json.RawMessage(test.data),
			})
			if !errors.Is(err, ErrInvalidPayload) || messages != nil {
				t.Fatalf("buildUserSyncMessages() = %+v, %v; want invalid payload", messages, err)
			}
		})
	}
}
