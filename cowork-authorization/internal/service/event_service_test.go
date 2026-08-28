package service

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"testing"

	"github.com/cowork/authorization/internal/config"
)

const testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

var errTest = errors.New("test error")

type fakePublisher struct {
	calls  int
	keys   []string
	values [][]byte
	err    error
}

func (f *fakePublisher) Publish(_ context.Context, key string, value []byte) error {
	f.calls++
	f.keys = append(f.keys, key)
	f.values = append(f.values, value)
	return f.err
}

type fakeStore struct {
	exists    bool
	existsErr error
	markErr   error
	markCalls int
}

func (f *fakeStore) Exists(_ string) (bool, error) {
	return f.exists, f.existsErr
}

func (f *fakeStore) MarkProcessed(_, _ string) (bool, error) {
	f.markCalls++
	return !f.exists, f.markErr
}

func newTestService(pub EventPublisher, store ProcessedEventStore) *EventService {
	return NewEventService(&config.AppConfig{DataGSMWebhookSecret: testSecret}, pub, store)
}

func sign(body []byte) string {
	mac := hmac.New(sha256.New, []byte(testSecret))
	mac.Write(body)
	return "sha256=" + hex.EncodeToString(mac.Sum(nil))
}

func TestVerifySignatureAcceptsOnlyAMatchingHMACSignature(t *testing.T) {
	t.Parallel()

	svc := newTestService(&fakePublisher{}, &fakeStore{})
	body := []byte(`{"hello":"world"}`)

	tests := []struct {
		name      string
		signature string
		want      bool
	}{
		{name: "valid signature matches", signature: sign(body), want: true},
		{name: "wrong secret is rejected", signature: "sha256=" + hex.EncodeToString([]byte("nope")), want: false},
		{name: "missing sha256 prefix is rejected", signature: hex.EncodeToString([]byte("abc")), want: false},
		{name: "empty signature is rejected", signature: "", want: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			if got := svc.VerifySignature(body, test.signature); got != test.want {
				t.Errorf("VerifySignature() = %v, want %v", got, test.want)
			}
		})
	}
}

func TestVerifySignatureFailsClosedWhenNoSecretIsConfigured(t *testing.T) {
	t.Parallel()

	svc := NewEventService(&config.AppConfig{}, &fakePublisher{}, &fakeStore{})
	body := []byte(`{}`)
	if svc.SecretConfigured() {
		t.Fatal("SecretConfigured() = true, want false")
	}
	if svc.VerifySignature(body, sign(body)) {
		t.Error("VerifySignature() = true, want false when no secret is configured")
	}
}

func envelope(t *testing.T, id, event string, objects ...string) []byte {
	t.Helper()
	oldItems := make([]map[string]any, 0, len(objects))
	newItems := make([]map[string]any, 0, len(objects))
	for i, object := range objects {
		oldItems = append(oldItems, map[string]any{
			"index":  i,
			"object": json.RawMessage(object),
		})
		newItems = append(newItems, map[string]any{
			"index":  i,
			"object": json.RawMessage(object),
		})
	}

	body, err := json.Marshal(WebhookEvent{
		ID:        id,
		Event:     event,
		Timestamp: "2026-08-26T01:02:03.123456Z",
		Data:      mustMarshalRaw(t, map[string]any{"old": oldItems, "new": newItems}),
	})
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func studentObject(studentID int64, email, role string) string {
	return fmt.Sprintf(
		`{"student_id":%d,"name":"홍길동","email":"%s","sex":"MAN","student_number":2105,"major":"SW_DEVELOPMENT","specialty":null,"role":"%s","github_id":"hong"}`,
		studentID,
		email,
		role,
	)
}

func mustMarshalRaw(t *testing.T, value any) json.RawMessage {
	t.Helper()
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func TestProcessEventPublishesMappedMessage(t *testing.T) {
	t.Parallel()

	pub := &fakePublisher{}
	store := &fakeStore{}
	svc := newTestService(pub, store)

	body := envelope(t, "evt_1", "student.updated", studentObject(1, "s24080@gsm.hs.kr", "STUDENT_COUNCIL"))
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Fatalf("ProcessEvent() error = %v", err)
	}

	if pub.calls != 1 {
		t.Fatalf("publish calls = %d, want 1", pub.calls)
	}
	if store.markCalls != 1 {
		t.Errorf("MarkProcessed should be called once after publish, got %d", store.markCalls)
	}
	if pub.keys[0] != "1" {
		t.Errorf("publish key = %q, want student_id", pub.keys[0])
	}

	var msg userSyncMessage
	if err := json.Unmarshal(pub.values[0], &msg); err != nil {
		t.Fatal(err)
	}
	if msg.EventType != "student.updated" || msg.StudentRole != "STUDENT_COUNCIL" || msg.DataGSMRefID != 1 {
		t.Errorf("unexpected sync message: %+v", msg)
	}
	if msg.OccurredAt != "2026-08-26T01:02:03.123456Z" {
		t.Errorf("occurred_at = %q", msg.OccurredAt)
	}
	if msg.StudentNumber == nil || *msg.StudentNumber != 2105 || msg.GithubID == nil || *msg.GithubID != "hong" {
		t.Errorf("student fields not mapped: %+v", msg)
	}
}

func TestProcessEventPublishesOneMessagePerStudentObject(t *testing.T) {
	t.Parallel()

	pub := &fakePublisher{}
	store := &fakeStore{}
	svc := newTestService(pub, store)

	body := envelope(
		t,
		"evt_batch",
		"student.updated",
		studentObject(1, "s24080@gsm.hs.kr", "GRADUATE"),
		studentObject(2, "s24081@gsm.hs.kr", "GRADUATE"),
	)
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Fatalf("ProcessEvent() error = %v", err)
	}

	if pub.calls != 2 {
		t.Fatalf("publish calls = %d, want 2", pub.calls)
	}
	if pub.keys[0] != "1" || pub.keys[1] != "2" {
		t.Errorf("publish keys = %v, want [1 2]", pub.keys)
	}
	if store.markCalls != 1 {
		t.Errorf("MarkProcessed should be called once for the envelope, got %d", store.markCalls)
	}
}

// TestProcessEventSkipsPublishingForNonPublishableInputs covers every input
// that must reach zero Publish calls, whether because the event is not one
// ProcessEvent maps (unsupported/duplicate) or because the payload itself
// fails validation before mapping (malformed envelope, timestamp, or
// student object).
func TestProcessEventSkipsPublishingForNonPublishableInputs(t *testing.T) {
	t.Parallel()

	const (
		validEmail = "x@gsm.hs.kr"
		validRole  = "GRADUATE"
	)

	badTimestampBody := func(t *testing.T) []byte {
		t.Helper()
		body := envelope(t, "evt_bad_time", "student.updated", studentObject(1, validEmail, validRole))
		var event map[string]any
		if err := json.Unmarshal(body, &event); err != nil {
			t.Fatal(err)
		}
		event["timestamp"] = "not-a-timestamp"
		return mustMarshalRaw(t, event)
	}

	missingStudentIDBody := func(t *testing.T) []byte {
		t.Helper()
		body, err := json.Marshal(WebhookEvent{
			ID:        "evt_4",
			Event:     "student.updated",
			Timestamp: "2026-08-26T01:02:03Z",
			Data:      json.RawMessage(`{"old":[{"index":0,"object":{}}],"new":[{"index":0,"object":{"student_id":0,"name":"홍길동","email":"x@gsm.hs.kr","sex":"MAN","role":"GENERAL_STUDENT"}}]}`),
		})
		if err != nil {
			t.Fatal(err)
		}
		return body
	}

	tests := []struct {
		name            string
		store           *fakeStore
		body            func(t *testing.T) []byte
		wantErr         bool
		wantInvalidType bool
	}{
		{
			name:  "unsupported event type is skipped without error",
			store: &fakeStore{},
			body: func(t *testing.T) []byte {
				return envelope(t, "evt_2", "club.updated", `{"club_id":1,"name":"더모먼트"}`)
			},
		},
		{
			name:  "already-processed event is skipped without error",
			store: &fakeStore{exists: true},
			body: func(t *testing.T) []byte {
				return envelope(t, "evt_3", "student.updated", studentObject(1, validEmail, validRole))
			},
		},
		{
			name:            "missing student_id is an invalid payload error",
			store:           &fakeStore{},
			body:            missingStudentIDBody,
			wantErr:         true,
			wantInvalidType: true,
		},
		{
			name:  "malformed envelope JSON is an invalid payload error",
			store: &fakeStore{},
			body: func(*testing.T) []byte {
				return []byte("not json")
			},
			wantErr:         true,
			wantInvalidType: true,
		},
		{
			name:            "non-RFC3339 timestamp is an invalid payload error",
			store:           &fakeStore{},
			body:            badTimestampBody,
			wantErr:         true,
			wantInvalidType: true,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			pub := &fakePublisher{}
			svc := newTestService(pub, test.store)

			err := svc.ProcessEvent(context.Background(), test.body(t))
			if (err != nil) != test.wantErr {
				t.Fatalf("ProcessEvent() error = %v, wantErr %v", err, test.wantErr)
			}
			if test.wantInvalidType && !errors.Is(err, ErrInvalidPayload) {
				t.Errorf("ProcessEvent() error = %v, want ErrInvalidPayload (400)", err)
			}
			if pub.calls != 0 {
				t.Errorf("publish calls = %d, want 0", pub.calls)
			}
		})
	}
}

func TestProcessEventToleratesMarkProcessedFailureAfterPublish(t *testing.T) {
	t.Parallel()

	pub := &fakePublisher{}
	store := &fakeStore{markErr: errTest}
	svc := newTestService(pub, store)

	body := envelope(t, "evt_5", "student.updated", studentObject(1, "x@gsm.hs.kr", "GRADUATE"))
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Errorf("ProcessEvent() error = %v, want nil when MarkProcessed fails after a successful publish", err)
	}
	if pub.calls != 1 {
		t.Errorf("publish calls = %d, want 1", pub.calls)
	}
}

func TestProcessEventReturnsInternalErrorWhenPublishFails(t *testing.T) {
	t.Parallel()

	pub := &fakePublisher{err: errTest}
	store := &fakeStore{}
	svc := newTestService(pub, store)

	body := envelope(t, "evt_6", "student.updated", studentObject(1, "x@gsm.hs.kr", "GRADUATE"))
	err := svc.ProcessEvent(context.Background(), body)
	if err == nil {
		t.Error("ProcessEvent() error = nil, want the publish failure")
	}
	if errors.Is(err, ErrInvalidPayload) {
		t.Error("ProcessEvent() classified a publish failure as ErrInvalidPayload, want an internal error (500)")
	}
	if store.markCalls != 0 {
		t.Errorf("MarkProcessed calls = %d, want 0 when publish fails", store.markCalls)
	}
}

func TestProcessEventReturnsErrorWhenExistsCheckFails(t *testing.T) {
	t.Parallel()

	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{existsErr: errTest})

	body := envelope(t, "evt_7", "student.updated", studentObject(1, "x@gsm.hs.kr", "GRADUATE"))
	if err := svc.ProcessEvent(context.Background(), body); err == nil {
		t.Error("ProcessEvent() error = nil, want the Exists() failure")
	}
	if pub.calls != 0 {
		t.Errorf("publish calls = %d, want 0 when Exists() fails", pub.calls)
	}
}
