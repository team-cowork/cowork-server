package service

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"testing"

	"github.com/cowork/authorization/internal/config"
)

const testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type fakePublisher struct {
	calls   int
	lastKey string
	lastVal []byte
	err     error
}

func (f *fakePublisher) Publish(_ context.Context, key string, value []byte) error {
	f.calls++
	f.lastKey = key
	f.lastVal = value
	return f.err
}

type fakeStore struct {
	isNew bool
	err   error
}

func (f *fakeStore) MarkProcessed(_, _ string) (bool, error) {
	return f.isNew, f.err
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

func TestResolveStudentRole(t *testing.T) {
	tests := []struct {
		event  string
		status string
		want   string
	}{
		{"student.graduated", "", "GRADUATE"},
		{"student.withdrawn", "", "WITHDRAWN"},
		{"student.status_changed", "STUDENT_COUNCIL", "STUDENT_COUNCIL"},
	}
	for _, tt := range tests {
		if got := resolveStudentRole(tt.event, tt.status); got != tt.want {
			t.Errorf("resolveStudentRole(%q, %q) = %q, want %q", tt.event, tt.status, got, tt.want)
		}
	}
}

func envelope(t *testing.T, id, event, email, status string) []byte {
	t.Helper()
	body, err := json.Marshal(WebhookEvent{
		ID:    id,
		Event: event,
		Data:  json.RawMessage(`{"student_id":1,"name":"홍길동","email":"` + email + `","status":"` + status + `"}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func TestProcessEvent_PublishesMappedMessage(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{isNew: true})

	body := envelope(t, "evt_1", "student.status_changed", "s24080@gsm.hs.kr", "STUDENT_COUNCIL")
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Fatalf("ProcessEvent() error = %v", err)
	}

	if pub.calls != 1 {
		t.Fatalf("publish calls = %d, want 1", pub.calls)
	}
	if pub.lastKey != "s24080@gsm.hs.kr" {
		t.Errorf("publish key = %q, want email", pub.lastKey)
	}

	var msg userSyncMessage
	if err := json.Unmarshal(pub.lastVal, &msg); err != nil {
		t.Fatal(err)
	}
	if msg.EventType != "student.status_changed" || msg.StudentRole != "STUDENT_COUNCIL" || msg.Email != "s24080@gsm.hs.kr" {
		t.Errorf("unexpected sync message: %+v", msg)
	}
}

func TestProcessEvent_UnsupportedEventSkipped(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{isNew: true})

	body := envelope(t, "evt_2", "club.created", "x@gsm.hs.kr", "")
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Fatalf("ProcessEvent() error = %v", err)
	}
	if pub.calls != 0 {
		t.Errorf("unsupported event should not publish, got %d calls", pub.calls)
	}
}

func TestProcessEvent_DuplicateSkipped(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{isNew: false})

	body := envelope(t, "evt_3", "student.graduated", "x@gsm.hs.kr", "")
	if err := svc.ProcessEvent(context.Background(), body); err != nil {
		t.Fatalf("ProcessEvent() error = %v", err)
	}
	if pub.calls != 0 {
		t.Errorf("duplicate event should not publish, got %d calls", pub.calls)
	}
}

func TestProcessEvent_MissingEmail(t *testing.T) {
	pub := &fakePublisher{}
	svc := newTestService(pub, &fakeStore{isNew: true})

	body := envelope(t, "evt_4", "student.graduated", "", "")
	if err := svc.ProcessEvent(context.Background(), body); err == nil {
		t.Error("ProcessEvent() expected error for missing email")
	}
	if pub.calls != 0 {
		t.Errorf("should not publish on missing email, got %d calls", pub.calls)
	}
}
