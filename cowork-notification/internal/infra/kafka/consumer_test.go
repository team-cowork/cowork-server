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

func TestBuildCalendarReminderUsesLeadAndEventTimeZone(t *testing.T) {
	cases := []struct {
		name string
		data map[string]any
		want string
	}{
		{
			name: "minutes before in event time zone",
			data: map[string]any{
				"title": "회고", "startAt": "2026-12-01T01:00:00Z", "timeZone": "Asia/Seoul",
				"allDay": false, "minutesBefore": float64(10),
			},
			want: "회고 일정이 10분 후에 시작합니다. 시작: 2026-12-01 10:00",
		},
		{
			name: "one day before all-day event",
			data: map[string]any{
				"title": "배포", "startAt": "2026-12-01T15:00:00Z", "timeZone": "Asia/Seoul",
				"allDay": true, "minutesBefore": float64(1440),
			},
			want: "배포 일정이 1일 후에 시작합니다. 시작: 2026-12-02",
		},
		{
			name: "starts now with unknown zone falls back to KST",
			data: map[string]any{
				"title": "회의", "startAt": "2026-12-01T01:00:00Z", "timeZone": "Nowhere/City",
				"minutesBefore": float64(0),
			},
			want: "회의 일정이 지금 시작합니다. 시작: 2026-12-01 10:00",
		},
		{
			name: "hour lead without start time",
			data: map[string]any{"title": "점검", "minutesBefore": float64(60)},
			want: "점검 일정이 1시간 후에 시작합니다.",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			title, body, ok := buildCalendarReminder(NotificationTriggerEvent{Type: "CALENDAR_EVENT_REMINDER", Data: tc.data})
			if !ok || title != "일정 알림" || body != tc.want {
				t.Fatalf("got (%q, %q, %v), want body %q", title, body, ok, tc.want)
			}
		})
	}
}

func TestBuildScheduledMessageFailedExplainsReason(t *testing.T) {
	cases := []struct {
		name string
		data map[string]any
		want string
	}{
		{
			name: "known reason with content",
			data: map[string]any{"reason": "CHANNEL_ARCHIVED", "content": "주간 공지"},
			want: "채널이 보관되었습니다. 내용: 주간 공지",
		},
		{
			name: "attachment only message",
			data: map[string]any{"reason": "DM_BLOCKED", "content": ""},
			want: "상대방이 회원님을 차단했습니다.",
		},
		{
			name: "unknown reason",
			data: map[string]any{"reason": "NEW_REASON", "content": "안내"},
			want: "알 수 없는 이유로 보내지 못했습니다. 내용: 안내",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			title, body, ok := buildScheduledMessageFailed(NotificationTriggerEvent{Type: "SCHEDULED_MESSAGE_FAILED", Data: tc.data})
			if !ok || title != "예약 메시지 발송 실패" || body != tc.want {
				t.Fatalf("got (%q, %q, %v), want body %q", title, body, ok, tc.want)
			}
		})
	}
}
