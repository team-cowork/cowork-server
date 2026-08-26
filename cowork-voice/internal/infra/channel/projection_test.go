package channel

import (
	"context"
	"errors"
	"testing"
	"time"
)

type recordingMembershipStore struct {
	upserted    *Membership
	deactivated *Membership
}

func (s *recordingMembershipStore) Upsert(_ context.Context, membership Membership) error {
	s.upserted = &membership
	return nil
}

func (s *recordingMembershipStore) Deactivate(_ context.Context, membership Membership) error {
	s.deactivated = &membership
	return nil
}

func TestEventHandler_JOIN_멤버십을_활성화한다(t *testing.T) {
	t.Parallel()

	store := &recordingMembershipStore{}
	handler := NewEventHandler(store)
	payload := []byte(`{"eventType":"JOIN","channelId":123,"teamId":456,"userId":42,"role":"MEMBER","channelType":"TEAM","occurredAt":"2026-08-26T12:34:56.123456Z"}`)

	if err := handler.Handle(context.Background(), "123:42", payload); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}
	if store.upserted == nil {
		t.Fatal("Upsert() was not called")
	}
	if store.upserted.ChannelID != 123 || store.upserted.TeamID != 456 || store.upserted.UserID != 42 {
		t.Fatalf("membership = %+v", *store.upserted)
	}
	wantOccurredAt := time.Date(2026, 8, 26, 12, 34, 56, 123456000, time.UTC)
	if !store.upserted.OccurredAt.Equal(wantOccurredAt) {
		t.Fatalf("occurred_at = %s, want %s", store.upserted.OccurredAt, wantOccurredAt)
	}
}

func TestEventHandler_LEAVE_멤버십_tombstone을_기록한다(t *testing.T) {
	t.Parallel()

	store := &recordingMembershipStore{}
	handler := NewEventHandler(store)
	payload := []byte(`{"eventType":"LEAVE","channelId":123,"teamId":456,"userId":42,"role":"MEMBER","channelType":"TEAM","occurredAt":"2026-08-26T12:34:56Z"}`)

	if err := handler.Handle(context.Background(), "123:42", payload); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}
	if store.deactivated == nil {
		t.Fatal("Deactivate() was not called")
	}
}

func TestEventHandler_잘못된_이벤트는_영구오류로_분류한다(t *testing.T) {
	t.Parallel()

	store := &recordingMembershipStore{}
	handler := NewEventHandler(store)
	err := handler.Handle(
		context.Background(),
		"123:42",
		[]byte(`{"eventType":"UNKNOWN","channelId":123,"teamId":456,"userId":42,"occurredAt":"2026-08-26T12:34:56Z"}`),
	)

	if !errors.Is(err, ErrInvalidEvent) {
		t.Fatalf("Handle() error = %v, want ErrInvalidEvent", err)
	}
	if store.upserted != nil || store.deactivated != nil {
		t.Fatal("invalid event mutated the projection")
	}
}

func TestEventHandler_legacy_key와_offset없는_시각은_거부한다(t *testing.T) {
	t.Parallel()

	store := &recordingMembershipStore{}
	handler := NewEventHandler(store)
	payload := []byte(`{"eventType":"JOIN","channelId":123,"teamId":456,"userId":42,"occurredAt":"2026-08-26T12:34:56"}`)

	if err := handler.Handle(context.Background(), "123", payload); !errors.Is(err, ErrInvalidEvent) {
		t.Fatalf("Handle() legacy key error = %v, want ErrInvalidEvent", err)
	}
	if err := handler.Handle(context.Background(), "123:42", payload); !errors.Is(err, ErrInvalidEvent) {
		t.Fatalf("Handle() offset-less time error = %v, want ErrInvalidEvent", err)
	}
	if store.upserted != nil || store.deactivated != nil {
		t.Fatal("legacy event mutated the projection")
	}
}
