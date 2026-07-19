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

func TestVerifySignature(t *testing.T) {
	svc := newTestService(&fakePublisher{}, &fakeStore{})
	body := []byte(`{"hello":"world"}`)

	tests := []struct {
		name      string
		signature string
		want      bool
	}{
		{"valid", sign(body), true},
		{"wrong secret", "sha256=" + hex.EncodeToString([]byte("nope")), false},
		{"missing prefix", hex.EncodeToString([]byte("abc")), false},
		{"empty", "", false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := svc.VerifySignature(body, tt.signature); got != tt.want {
				t.Errorf("VerifySignature() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestVerifySignature_NoSecretConfigured(t *testing.T) {
	svc := NewEventService(&config.AppConfig{}, &fakePublisher{}, &fakeStore{})
	body := []byte(`{}`)
	if svc.SecretConfigured() {
		t.Fatal("SecretConfigured() should be false")
	}
	if svc.VerifySignature(body, sign(body)) {
		t.Error("VerifySignature() should fail when no secret configured")
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
		ID:    id,
		Event: event,
		Data:  mustMarshalRaw(t, map[string]any{"old": oldItems, "new": newItems}),
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

func TestProcessEvent_PublishesMappedMessage(t *testing.T) {
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
	if msg.StudentNumber == nil || *msg.StudentNumber != 2105 || msg.GithubID == nil || *msg.GithubID != "hong" {
		t.Errorf("student fields not mapped: %+v", msg)
	}
}

func TestProcessEvent_PublishesOneMessagePerStudentObject(t *testing.T) {
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

func TestProcessEvent_UnsupportedEventSkipped(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{})

	body := envelope(t, "evt_2", "club.updated", `{"club_id":1,"name":"더모먼트"}`)
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Fatalf("ProcessEvent() error = %v", err)
	}
	if pub.calls != 0 {
		t.Errorf("unsupported event should not publish, got %d calls", pub.calls)
	}
}

func TestProcessEvent_DuplicateSkipped(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{exists: true})

	body := envelope(t, "evt_3", "student.updated", studentObject(1, "x@gsm.hs.kr", "GRADUATE"))
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Fatalf("ProcessEvent() error = %v", err)
	}
	if pub.calls != 0 {
		t.Errorf("duplicate event should not publish, got %d calls", pub.calls)
	}
}

func TestProcessEvent_MissingStudentID(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{})

	body, err := json.Marshal(WebhookEvent{
		ID:    "evt_4",
		Event: "student.updated",
		Data:  json.RawMessage(`{"old":[{"index":0,"object":{}}],"new":[{"index":0,"object":{"student_id":0,"name":"홍길동","email":"x@gsm.hs.kr","sex":"MAN","role":"GENERAL_STUDENT"}}]}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	err = svc.ProcessEvent(context.Background(), body)
	if err == nil {
		t.Error("ProcessEvent() expected error for missing student_id")
	}
	if !errors.Is(err, ErrInvalidPayload) {
		t.Errorf("missing student_id should be ErrInvalidPayload (400), got %v", err)
	}
	if pub.calls != 0 {
		t.Errorf("should not publish on missing student_id, got %d calls", pub.calls)
	}
}

func TestProcessEvent_InvalidEnvelopeIsPayloadError(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{})

	err := svc.ProcessEvent(context.Background(), []byte("not json"))
	if !errors.Is(err, ErrInvalidPayload) {
		t.Errorf("invalid envelope should be ErrInvalidPayload (400), got %v", err)
	}
	if pub.calls != 0 {
		t.Errorf("should not publish on invalid envelope, got %d calls", pub.calls)
	}
}

func TestProcessEvent_MarkErrorAfterPublishIsNonFatal(t *testing.T) {
	pub := &fakePublisher{}
	store := &fakeStore{markErr: errTest}
	svc := newTestService(pub, store)

	body := envelope(t, "evt_5", "student.updated", studentObject(1, "x@gsm.hs.kr", "GRADUATE"))
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Errorf("ProcessEvent() should not fail when mark fails after publish, got %v", err)
	}
	if pub.calls != 1 {
		t.Errorf("publish calls = %d, want 1", pub.calls)
	}
}

func TestProcessEvent_PublishErrorReturnsError(t *testing.T) {
	pub := &fakePublisher{err: errTest}
	store := &fakeStore{}
	svc := newTestService(pub, store)

	body := envelope(t, "evt_6", "student.updated", studentObject(1, "x@gsm.hs.kr", "GRADUATE"))
	err := svc.ProcessEvent(context.Background(), body)
	if err == nil {
		t.Error("ProcessEvent() expected error when publish fails")
	}
	if errors.Is(err, ErrInvalidPayload) {
		t.Error("publish failure is an internal error (500), not ErrInvalidPayload")
	}
	if store.markCalls != 0 {
		t.Errorf("MarkProcessed must not run when publish fails, got %d", store.markCalls)
	}
}

func TestProcessEvent_ExistsErrorReturnsError(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{existsErr: errTest})

	body := envelope(t, "evt_7", "student.updated", studentObject(1, "x@gsm.hs.kr", "GRADUATE"))
	if err := svc.ProcessEvent(context.Background(), body); err == nil {
		t.Error("ProcessEvent() expected error when Exists fails")
	}
	if pub.calls != 0 {
		t.Errorf("should not publish when Exists fails, got %d calls", pub.calls)
	}
}
