package webhook

import (
	"context"
	"testing"
	"time"

	livekit "github.com/livekit/protocol/livekit"

	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

func TestHandleEvent_참가_이벤트_첫_입장이면_세션시작과_유저입장을_발행한다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{
		findSessionByRoomNameResult: &roomdomain.VoiceSession{
			SessionID: "session-1",
			ChannelID: 123,
			TeamID:    456,
			RoomName:  "voice-123-session-1",
			Status:    roomdomain.StatusActive,
			StartedAt: time.Unix(1700000000, 0).UTC(),
		},
		markSessionStartedResult: true,
	}
	publisher := &stubPublisher{}
	svc := NewWebhookService(repo, publisher)
	now := time.Unix(1700000300, 0).UTC()
	svc.now = func() time.Time { return now }

	svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event: "participant_joined",
		Room: &livekit.Room{
			Name: "voice-123-session-1",
		},
		Participant: &livekit.ParticipantInfo{
			Identity: "42",
		},
	})

	if len(publisher.messages) != 2 {
		t.Fatalf("published messages = %d, want 2", len(publisher.messages))
	}
	if _, ok := publisher.messages[0].event.(*kafkadomain.SessionStartedEvent); !ok {
		t.Fatalf("first event type = %T, want *SessionStartedEvent", publisher.messages[0].event)
	}
	if _, ok := publisher.messages[1].event.(*kafkadomain.UserJoinedEvent); !ok {
		t.Fatalf("second event type = %T, want *UserJoinedEvent", publisher.messages[1].event)
	}
}

func TestHandleEvent_퇴장_이벤트가_중복이면_아무것도_발행하지_않는다(t *testing.T) {
	t.Parallel()

	joinedAt := time.Unix(1700000000, 0).UTC()
	repo := &stubRepository{
		findSessionByRoomNameResult: &roomdomain.VoiceSession{
			SessionID: "session-1",
			ChannelID: 123,
			TeamID:    456,
			RoomName:  "voice-123-session-1",
			Status:    roomdomain.StatusActive,
			StartedAt: joinedAt,
		},
		getParticipantJoinedAtResult: &joinedAt,
		markParticipantLeftResult:    false,
	}
	publisher := &stubPublisher{}
	svc := NewWebhookService(repo, publisher)
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event: "participant_left",
		Room: &livekit.Room{
			Name: "voice-123-session-1",
		},
		Participant: &livekit.ParticipantInfo{
			Identity: "42",
		},
	})

	if len(publisher.messages) != 0 {
		t.Fatalf("published messages = %d, want 0", len(publisher.messages))
	}
}

func TestHandleEvent_퇴장_이벤트_첫_처리면_USER_LEFT를_발행한다(t *testing.T) {
	t.Parallel()

	joinedAt := time.Unix(1700000000, 0).UTC()
	repo := &stubRepository{
		findSessionByRoomNameResult: &roomdomain.VoiceSession{
			SessionID: "session-1",
			ChannelID: 123,
			TeamID:    456,
			RoomName:  "voice-123-session-1",
			Status:    roomdomain.StatusActive,
			StartedAt: joinedAt,
		},
		getParticipantJoinedAtResult: &joinedAt,
		markParticipantLeftResult:    true,
	}
	publisher := &stubPublisher{}
	svc := NewWebhookService(repo, publisher)
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event: "participant_left",
		Room: &livekit.Room{
			Name: "voice-123-session-1",
		},
		Participant: &livekit.ParticipantInfo{
			Identity: "42",
		},
	})

	if len(publisher.messages) != 1 {
		t.Fatalf("published messages = %d, want 1", len(publisher.messages))
	}
	left, ok := publisher.messages[0].event.(*kafkadomain.UserLeftEvent)
	if !ok {
		t.Fatalf("event type = %T, want *UserLeftEvent", publisher.messages[0].event)
	}
	if left.DurationSeconds != 300 {
		t.Fatalf("duration_seconds = %d, want 300", left.DurationSeconds)
	}
}

func TestHandleEvent_룸종료_이벤트면_세션종료와_정리후_이벤트를_발행한다(t *testing.T) {
	t.Parallel()

	startedAt := time.Unix(1700000000, 0).UTC()
	now := time.Unix(1700000600, 0).UTC()
	repo := &stubRepository{
		findSessionByRoomNameResult: &roomdomain.VoiceSession{
			SessionID: "session-1",
			ChannelID: 123,
			TeamID:    456,
			RoomName:  "voice-123-session-1",
			Status:    roomdomain.StatusActive,
			StartedAt: startedAt,
		},
		cleanupOrphanParticipantsResult: 2,
	}
	publisher := &stubPublisher{}
	svc := NewWebhookService(repo, publisher)
	svc.now = func() time.Time { return now }

	svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event: "room_finished",
		Room: &livekit.Room{
			Name: "voice-123-session-1",
		},
	})

	if repo.endSessionSessionID != "session-1" {
		t.Fatalf("EndSession() sessionID = %q, want session-1", repo.endSessionSessionID)
	}
	if repo.cleanupSessionID != "session-1" {
		t.Fatalf("CleanupOrphanParticipants() sessionID = %q, want session-1", repo.cleanupSessionID)
	}
	if len(publisher.messages) != 1 {
		t.Fatalf("published messages = %d, want 1", len(publisher.messages))
	}
	ended, ok := publisher.messages[0].event.(*kafkadomain.SessionEndedEvent)
	if !ok {
		t.Fatalf("event type = %T, want *SessionEndedEvent", publisher.messages[0].event)
	}
	if ended.DurationSeconds != 600 {
		t.Fatalf("duration_seconds = %d, want 600", ended.DurationSeconds)
	}
}

type stubPublisher struct {
	messages []publishedMessage
	err      error
}

type publishedMessage struct {
	sessionID string
	event     any
}

func (s *stubPublisher) Publish(_ context.Context, sessionID string, v any) error {
	if s.err != nil {
		return s.err
	}
	s.messages = append(s.messages, publishedMessage{
		sessionID: sessionID,
		event:     v,
	})
	return nil
}

type stubRepository struct {
	findSessionByRoomNameResult      *roomdomain.VoiceSession
	findSessionByRoomNameErr         error
	markSessionStartedResult         bool
	markSessionStartedErr            error
	getParticipantJoinedAtResult     *time.Time
	getParticipantJoinedAtErr        error
	markParticipantLeftResult        bool
	markParticipantLeftErr           error
	cleanupOrphanParticipantsResult  int64
	cleanupOrphanParticipantsErr     error
	endSessionErr                    error
	endSessionSessionID              string
	cleanupSessionID                 string
}

func (s *stubRepository) FindSessionByRoomName(_ context.Context, _ string) (*roomdomain.VoiceSession, error) {
	return s.findSessionByRoomNameResult, s.findSessionByRoomNameErr
}

func (s *stubRepository) MarkSessionStarted(_ context.Context, _ string, _ time.Time) (bool, error) {
	return s.markSessionStartedResult, s.markSessionStartedErr
}

func (s *stubRepository) GetParticipantJoinedAt(_ context.Context, _ string, _ int64) (*time.Time, error) {
	return s.getParticipantJoinedAtResult, s.getParticipantJoinedAtErr
}

func (s *stubRepository) MarkParticipantLeft(_ context.Context, _ string, _ int64, _ time.Time) (bool, error) {
	return s.markParticipantLeftResult, s.markParticipantLeftErr
}

func (s *stubRepository) EndSession(_ context.Context, sessionID string, _ time.Time) error {
	s.endSessionSessionID = sessionID
	return s.endSessionErr
}

func (s *stubRepository) CleanupOrphanParticipants(_ context.Context, sessionID string, _ time.Time) (int64, error) {
	s.cleanupSessionID = sessionID
	return s.cleanupOrphanParticipantsResult, s.cleanupOrphanParticipantsErr
}
