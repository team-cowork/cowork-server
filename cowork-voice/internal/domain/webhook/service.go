package webhook

import (
	"context"
	"errors"
	"log/slog"
	"strconv"
	"time"

	livekit "github.com/livekit/protocol/livekit"

	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

type WebhookService struct {
	repo SessionRepository
	now  func() time.Time
}

func NewWebhookService(repo SessionRepository) *WebhookService {
	return &WebhookService{
		repo: repo,
		now: func() time.Time {
			return time.Now().UTC()
		},
	}
}

func (s *WebhookService) HandleEvent(ctx context.Context, event *livekit.WebhookEvent) error {
	now := s.now()
	nowStr := now.Format(time.RFC3339)

	switch WebhookEventType(event.GetEvent()) {
	case EventParticipantJoined:
		return s.handleParticipantJoined(ctx, event, now, nowStr)
	case EventParticipantLeft:
		return s.handleParticipantLeft(ctx, event, now, nowStr)
	case EventRoomFinished:
		return s.handleRoomFinished(ctx, event, now, nowStr)
	}
	return nil
}

func (s *WebhookService) handleParticipantJoined(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) error {
	participant := event.GetParticipant()
	if participant == nil {
		return nil
	}
	room := event.GetRoom()
	if room == nil {
		return nil
	}

	userID, err := strconv.ParseInt(participant.Identity, 10, 64)
	if err != nil {
		return nil
	}
	parsedRoom, ok := roomdomain.ParseRoomName(room.Name)
	if !ok {
		return nil
	}

	voiceSession, err := s.findSession(ctx, room.Name)
	if err != nil {
		slog.Error("participant_joined: failed to find session", "err", err, "room_name", room.Name)
		return errors.New("internal server error")
	}
	if voiceSession == nil {
		slog.Warn("participant_joined: voice session not found", "room_name", room.Name, "channel_id", parsedRoom.ChannelID)
		return nil
	}

	_, err = s.repo.MarkSessionStartedAndEnqueue(ctx, voiceSession.SessionID, now, &kafkadomain.SessionStartedEvent{
		EventType: kafkadomain.EventSessionStarted,
		SessionID: voiceSession.SessionID,
		ChannelID: parsedRoom.ChannelID,
		TeamID:    voiceSession.TeamID,
		UserID:    userID,
		Timestamp: nowStr,
	})
	if err != nil {
		slog.Error("failed to mark session started and enqueue event", "err", err, "session_id", voiceSession.SessionID)
		return err
	}
	_, err = s.repo.RecordParticipantJoinedAndEnqueue(ctx, &roomdomain.VoiceParticipant{
		SessionID: voiceSession.SessionID,
		UserID:    userID,
		ChannelID: parsedRoom.ChannelID,
		JoinedAt:  now,
	}, participantOccurrenceID(event), &kafkadomain.UserJoinedEvent{
		EventType: kafkadomain.EventUserJoined,
		SessionID: voiceSession.SessionID,
		ChannelID: parsedRoom.ChannelID,
		TeamID:    voiceSession.TeamID,
		UserID:    userID,
		Timestamp: nowStr,
	})
	if err != nil {
		slog.Error("failed to record participant and enqueue USER_JOINED", "err", err)
		return err
	}
	return nil
}

func (s *WebhookService) handleParticipantLeft(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) error {
	participant := event.GetParticipant()
	if participant == nil {
		return nil
	}
	room := event.GetRoom()
	if room == nil {
		return nil
	}

	userID, err := strconv.ParseInt(participant.Identity, 10, 64)
	if err != nil {
		return nil
	}
	parsedRoom, ok := roomdomain.ParseRoomName(room.Name)
	if !ok {
		return nil
	}

	voiceSession, err := s.findSession(ctx, room.Name)
	if err != nil {
		slog.Error("participant_left: failed to find session", "err", err, "room_name", room.Name)
		return errors.New("internal server error")
	}
	if voiceSession == nil {
		slog.Warn("participant_left: voice session not found", "room_name", room.Name, "channel_id", parsedRoom.ChannelID)
		return nil
	}

	occurrenceID := participantOccurrenceID(event)
	joinedAt, err := s.repo.GetParticipantJoinedAt(ctx, voiceSession.SessionID, userID, occurrenceID)
	if err != nil {
		slog.Error("failed to get participant joined_at", "err", err)
		return err
	}

	var durationSeconds int64
	if joinedAt != nil {
		// clock skew 등으로 now < joinedAt이면 음수가 될 수 있어 0으로 보정한다.
		if diff := now.Sub(*joinedAt).Seconds(); diff > 0 {
			durationSeconds = int64(diff)
		}
	}

	_, err = s.repo.MarkParticipantLeftAndEnqueue(ctx, voiceSession.SessionID, userID, occurrenceID, now, &kafkadomain.UserLeftEvent{
		EventType:       kafkadomain.EventUserLeft,
		SessionID:       voiceSession.SessionID,
		ChannelID:       parsedRoom.ChannelID,
		TeamID:          voiceSession.TeamID,
		UserID:          userID,
		DurationSeconds: durationSeconds,
		Timestamp:       nowStr,
	})
	if err != nil {
		slog.Error("failed to mark participant left and enqueue USER_LEFT", "err", err)
		return err
	}
	return nil
}

func (s *WebhookService) handleRoomFinished(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) error {
	room := event.GetRoom()
	if room == nil {
		return nil
	}

	parsedRoom, ok := roomdomain.ParseRoomName(room.Name)
	if !ok {
		return nil
	}

	voiceSession, err := s.findSession(ctx, room.Name)
	if err != nil {
		slog.Error("room_finished: failed to find session", "err", err, "room_name", room.Name)
		return errors.New("internal server error")
	}
	if voiceSession == nil {
		return nil
	}

	var durationSeconds int64
	// clock skew 등으로 now < StartedAt이면 음수가 될 수 있어 0으로 보정한다.
	if diff := now.Sub(voiceSession.StartedAt).Seconds(); diff > 0 {
		durationSeconds = int64(diff)
	}

	ended, err := s.repo.EndSessionAndEnqueue(ctx, voiceSession.SessionID, now, &kafkadomain.SessionEndedEvent{
		EventType:       kafkadomain.EventSessionEnded,
		SessionID:       voiceSession.SessionID,
		ChannelID:       parsedRoom.ChannelID,
		TeamID:          voiceSession.TeamID,
		DurationSeconds: durationSeconds,
		Timestamp:       nowStr,
	})
	if err != nil {
		slog.Error("failed to end session", "err", err, "session_id", voiceSession.SessionID)
		return err
	}
	if !ended {
		// 이미 종료된 세션(room_finished 재전송) → 정리·발행을 반복하지 않는다.
		return nil
	}

	count, err := s.repo.CleanupOrphanParticipants(ctx, voiceSession.SessionID, now)
	if err != nil {
		slog.Error("failed to cleanup orphan participants", "err", err)
	} else if count > 0 {
		slog.Info("orphan participants cleaned up", "session_id", voiceSession.SessionID, "count", count)
	}

	return nil
}

func (s *WebhookService) findSession(ctx context.Context, roomName string) (*roomdomain.VoiceSession, error) {
	return s.repo.FindSessionByRoomName(ctx, roomName)
}
