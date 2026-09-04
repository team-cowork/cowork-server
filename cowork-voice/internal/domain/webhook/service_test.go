package webhook

import (
	"context"
	"testing"
	"time"

	livekit "github.com/livekit/protocol/livekit"

	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

func TestWebhookService(t *testing.T) {
	t.Run("HandleEvent - 참가 이벤트면 세션 시작과 사용자 입장을 발행한다", func(t *testing.T) {
		t.Parallel()

		repo := &stubSessionRepository{
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
		svc := NewWebhookService(repo)
		now := time.Unix(1700000300, 0).UTC()
		svc.now = func() time.Time { return now }

		if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
			Event: "participant_joined",
			Room: &livekit.Room{
				Name: "voice-123-session-1",
			},
			Participant: &livekit.ParticipantInfo{
				Identity: "42",
			},
		}); err != nil {
			t.Fatalf("HandleEvent() error = %v", err)
		}

		if len(repo.messages) != 2 {
			t.Fatalf("enqueued messages = %d, want 2", len(repo.messages))
		}
		if _, ok := repo.messages[0].event.(*kafkadomain.SessionStartedEvent); !ok {
			t.Fatalf("first event type = %T, want *SessionStartedEvent", repo.messages[0].event)
		}
		if _, ok := repo.messages[1].event.(*kafkadomain.UserJoinedEvent); !ok {
			t.Fatalf("second event type = %T, want *UserJoinedEvent", repo.messages[1].event)
		}
	})

	t.Run("HandleEvent - 퇴장 이벤트면 USER_LEFT를 발행한다", func(t *testing.T) {
		t.Parallel()

		joinedAt := time.Unix(1700000000, 0).UTC()
		repo := &stubSessionRepository{
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
		svc := NewWebhookService(repo)
		svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

		if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
			Event: "participant_left",
			Room: &livekit.Room{
				Name: "voice-123-session-1",
			},
			Participant: &livekit.ParticipantInfo{
				Identity: "42",
			},
		}); err != nil {
			t.Fatalf("HandleEvent() error = %v", err)
		}

		if len(repo.messages) != 1 {
			t.Fatalf("enqueued messages = %d, want 1", len(repo.messages))
		}
		left, ok := repo.messages[0].event.(*kafkadomain.UserLeftEvent)
		if !ok {
			t.Fatalf("event type = %T, want *UserLeftEvent", repo.messages[0].event)
		}
		if left.DurationSeconds != 300 {
			t.Fatalf("duration_seconds = %d, want 300", left.DurationSeconds)
		}
	})

	t.Run("HandleEvent - 룸 종료 이벤트면 세션 종료와 정리 후 이벤트를 발행한다", func(t *testing.T) {
		t.Parallel()

		startedAt := time.Unix(1700000000, 0).UTC()
		now := time.Unix(1700000600, 0).UTC()
		repo := &stubSessionRepository{
			findSessionByRoomNameResult: &roomdomain.VoiceSession{
				SessionID: "session-1",
				ChannelID: 123,
				TeamID:    456,
				RoomName:  "voice-123-session-1",
				Status:    roomdomain.StatusActive,
				StartedAt: startedAt,
			},
			endSessionResult:                true,
			cleanupOrphanParticipantsResult: 2,
		}
		svc := NewWebhookService(repo)
		svc.now = func() time.Time { return now }

		if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
			Event: "room_finished",
			Room: &livekit.Room{
				Name: "voice-123-session-1",
			},
		}); err != nil {
			t.Fatalf("HandleEvent() error = %v", err)
		}

		if repo.endSessionSessionID != "session-1" {
			t.Fatalf("EndSession() sessionID = %q, want session-1", repo.endSessionSessionID)
		}
		if repo.cleanupSessionID != "session-1" {
			t.Fatalf("CleanupOrphanParticipants() sessionID = %q, want session-1", repo.cleanupSessionID)
		}
		if len(repo.messages) != 1 {
			t.Fatalf("enqueued messages = %d, want 1", len(repo.messages))
		}
		ended, ok := repo.messages[0].event.(*kafkadomain.SessionEndedEvent)
		if !ok {
			t.Fatalf("event type = %T, want *SessionEndedEvent", repo.messages[0].event)
		}
		if ended.DurationSeconds != 600 {
			t.Fatalf("duration_seconds = %d, want 600", ended.DurationSeconds)
		}
	})
}

type publishedMessage struct {
	sessionID string
	event     any
}

type stubSessionRepository struct {
	findSessionByRoomNameResult     *roomdomain.VoiceSession
	findSessionByRoomNameErr        error
	markSessionStartedResult        bool
	markSessionStartedErr           error
	getParticipantJoinedAtResult    *time.Time
	getParticipantJoinedAtErr       error
	markParticipantLeftResult       bool
	markParticipantLeftErr          error
	cleanupOrphanParticipantsResult int64
	cleanupOrphanParticipantsErr    error
	endSessionResult                bool
	endSessionErr                   error
	endSessionSessionID             string
	cleanupSessionID                string
	messages                        []publishedMessage
}

func (s *stubSessionRepository) FindSessionByRoomName(_ context.Context, _ string) (*roomdomain.VoiceSession, error) {
	return s.findSessionByRoomNameResult, s.findSessionByRoomNameErr
}

func (s *stubSessionRepository) MarkSessionStartedAndEnqueue(
	_ context.Context,
	sessionID string,
	_ time.Time,
	event any,
) (bool, error) {
	if s.markSessionStartedResult && s.markSessionStartedErr == nil {
		s.messages = append(s.messages, publishedMessage{sessionID: sessionID, event: event})
	}
	return s.markSessionStartedResult, s.markSessionStartedErr
}

func (s *stubSessionRepository) RecordParticipantJoinedAndEnqueue(
	_ context.Context,
	participant *roomdomain.VoiceParticipant,
	_ string,
	event any,
) (bool, error) {
	s.messages = append(s.messages, publishedMessage{sessionID: participant.SessionID, event: event})
	return true, nil
}

func (s *stubSessionRepository) GetParticipantJoinedAt(
	_ context.Context,
	_ string,
	_ int64,
	_ string,
) (*time.Time, error) {
	return s.getParticipantJoinedAtResult, s.getParticipantJoinedAtErr
}

func (s *stubSessionRepository) MarkParticipantLeftAndEnqueue(
	_ context.Context,
	sessionID string,
	_ int64,
	_ string,
	_ time.Time,
	event any,
) (bool, error) {
	if s.markParticipantLeftResult && s.markParticipantLeftErr == nil {
		s.messages = append(s.messages, publishedMessage{sessionID: sessionID, event: event})
	}
	return s.markParticipantLeftResult, s.markParticipantLeftErr
}

func (s *stubSessionRepository) EndSessionAndEnqueue(
	_ context.Context,
	sessionID string,
	_ time.Time,
	event any,
) (bool, error) {
	s.endSessionSessionID = sessionID
	if s.endSessionResult && s.endSessionErr == nil {
		s.messages = append(s.messages, publishedMessage{sessionID: sessionID, event: event})
	}
	return s.endSessionResult, s.endSessionErr
}

func (s *stubSessionRepository) CleanupOrphanParticipants(_ context.Context, sessionID string, _ time.Time) (int64, error) {
	s.cleanupSessionID = sessionID
	return s.cleanupOrphanParticipantsResult, s.cleanupOrphanParticipantsErr
}
