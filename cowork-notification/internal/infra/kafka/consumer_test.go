package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	segkafka "github.com/segmentio/kafka-go"
)

type fakeNotifier struct {
	calls int
	err   error
}

func (f *fakeNotifier) Notify(context.Context, string, []int64, []int64, string, string, int64, int64) ([]int64, error) {
	f.calls++
	return []int64{7}, f.err
}

type fakeInbox struct {
	completed map[string]bool
}

func (f *fakeInbox) IsCompleted(_ context.Context, eventID string) (bool, error) {
	return f.completed[eventID], nil
}

func (f *fakeInbox) MarkCompleted(_ context.Context, eventID string) error {
	f.completed[eventID] = true
	return nil
}

type fakeBroadcaster struct {
	payloads [][]byte
}

func (f *fakeBroadcaster) Broadcast(_ []int64, payload []byte) {
	f.payloads = append(f.payloads, payload)
}

func newTestConsumer(svc *fakeNotifier) (*Consumer, *fakeInbox, *fakeBroadcaster) {
	inbox := &fakeInbox{completed: map[string]bool{}}
	sse := &fakeBroadcaster{}
	return &Consumer{svc: svc, inbox: inbox, sseBroadcaster: sse}, inbox, sse
}

func triggerMessage(eventID string) segkafka.Message {
	return segkafka.Message{Value: []byte(`{"eventId":"` + eventID + `","type":"MEMBER_INVITED","targetUserIds":[7]}`)}
}

func TestHandleSkipsRedeliveredCompletedEvent(t *testing.T) {
	svc := &fakeNotifier{}
	c, _, sse := newTestConsumer(svc)
	ctx := context.Background()

	for range 2 {
		if err := c.handle(ctx, ctx, triggerMessage("evt-1"), nil); err != nil {
			t.Fatalf("handle: %v", err)
		}
	}

	if svc.calls != 1 || len(sse.payloads) != 1 {
		t.Fatalf("redelivery fanned out again: notify=%d sse=%d", svc.calls, len(sse.payloads))
	}
	var payload map[string]any
	if err := json.Unmarshal(sse.payloads[0], &payload); err != nil || payload["eventId"] != "evt-1" {
		t.Fatalf("SSE payload must carry eventId, got %s", sse.payloads[0])
	}
}

func TestHandleLeavesFailedFanOutUncompleted(t *testing.T) {
	svc := &fakeNotifier{err: errors.New("preferences unavailable")}
	c, inbox, sse := newTestConsumer(svc)
	ctx := context.Background()

	if err := c.handle(ctx, ctx, triggerMessage("evt-1"), nil); err == nil {
		t.Fatal("expected Notify error")
	}

	if inbox.completed["evt-1"] || len(sse.payloads) != 0 {
		t.Fatal("failed fan-out must stay retryable")
	}
}

func TestHandleRejectsEventWithoutEventID(t *testing.T) {
	svc := &fakeNotifier{}
	c, inbox, sse := newTestConsumer(svc)
	ctx := context.Background()

	err := c.handle(ctx, ctx, triggerMessage(""), nil)

	if !errors.Is(err, errInvalidNotificationEvent) {
		t.Fatalf("event without eventId must be reported as invalid, got %v", err)
	}
	if svc.calls != 0 || len(sse.payloads) != 0 || len(inbox.completed) != 0 {
		t.Fatalf("event without eventId must not be sent: notify=%d sse=%d inbox=%v", svc.calls, len(sse.payloads), inbox.completed)
	}
	if err := c.handleWithRetry(ctx, triggerMessage(""), &unfencedLease{ctx: ctx}); err != nil {
		t.Fatalf("invalid event must be skipped so its offset is committed, got %v", err)
	}
}
