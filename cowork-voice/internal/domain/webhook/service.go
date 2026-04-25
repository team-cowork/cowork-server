package webhook

import (
	"context"
	"log/slog"
	"strconv"
	"time"

	livekit "github.com/livekit/protocol/livekit"

	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

type EventPublisher interface {
	Publish(ctx context.Context, sessionID string, v any) error
}

type WebhookService struct {
	repo  SessionRepository
	kafka EventPublisher
	now   func() time.Time
}

func NewWebhookService(repo SessionRepository, kafka EventPublisher) *WebhookService {
	return &WebhookService{
		repo:  repo,
		kafka: kafka,
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
	if err != nil || voiceSession == nil {
		slog.Warn("participant_joined: voice session not found", "room_name", room.Name, "channel_id", parsedRoom.ChannelID)
		return nil
	}

	firstStart, err := s.repo.MarkSessionStarted(ctx, voiceSession.SessionID, now)
	if err != nil {
		slog.Error("failed to mark session started", "err", err, "session_id", voiceSession.SessionID)
		return err
	}
	if firstStart {
		if err := s.kafka.Publish(ctx, voiceSession.SessionID, &kafkadomain.SessionStartedEvent{
			EventType: kafkadomain.EventSessionStarted,
			SessionID: voiceSession.SessionID,
			ChannelID: parsedRoom.ChannelID,
			TeamID:    voiceSession.TeamID,
			UserID:    userID,
			Timestamp: nowStr,
		}); err != nil {
			slog.Error("failed to publish SESSION_STARTED", "err", err)
		}
	}

	if err := s.kafka.Publish(ctx, voiceSession.SessionID, &kafkadomain.UserJoinedEvent{
		EventType: kafkadomain.EventUserJoined,
		SessionID: voiceSession.SessionID,
		ChannelID: parsedRoom.ChannelID,
		TeamID:    voiceSession.TeamID,
		UserID:    userID,
		Timestamp: nowStr,
	}); err != nil {
		slog.Error("failed to publish USER_JOINED", "err", err)
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
	if err != nil || voiceSession == nil {
		slog.Warn("participant_left: voice session not found", "room_name", room.Name, "channel_id", parsedRoom.ChannelID)
		return nil
	}

	joinedAt, err := s.repo.GetParticipantJoinedAt(ctx, voiceSession.SessionID, userID)
	if err != nil {
		slog.Error("failed to get participant joined_at", "err", err)
	}

	firstLeave, err := s.repo.MarkParticipantLeft(ctx, voiceSession.SessionID, userID, now)
	if err != nil {
		slog.Error("failed to mark participant left", "err", err)
		return err
	}
	if !firstLeave {
		return nil
	}

	var durationSeconds int64
	if joinedAt != nil {
		durationSeconds = int64(now.Sub(*joinedAt).Seconds())
	}

	if err := s.kafka.Publish(ctx, voiceSession.SessionID, &kafkadomain.UserLeftEvent{
		EventType:       kafkadomain.EventUserLeft,
		SessionID:       voiceSession.SessionID,
		ChannelID:       parsedRoom.ChannelID,
		TeamID:          voiceSession.TeamID,
		UserID:          userID,
		DurationSeconds: durationSeconds,
		Timestamp:       nowStr,
	}); err != nil {
		slog.Error("failed to publish USER_LEFT", "err", err)
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
	if err != nil || voiceSession == nil {
		return nil
	}

	if err := s.repo.EndSession(ctx, voiceSession.SessionID, now); err != nil {
		slog.Error("failed to end session", "err", err, "session_id", voiceSession.SessionID)
		return err
	}

	count, err := s.repo.CleanupOrphanParticipants(ctx, voiceSession.SessionID, now)
	if err != nil {
		slog.Error("failed to cleanup orphan participants", "err", err)
	} else if count > 0 {
		slog.Info("orphan participants cleaned up", "session_id", voiceSession.SessionID, "count", count)
	}

	durationSeconds := int64(now.Sub(voiceSession.StartedAt).Seconds())

	if err := s.kafka.Publish(ctx, voiceSession.SessionID, &kafkadomain.SessionEndedEvent{
		EventType:       kafkadomain.EventSessionEnded,
		SessionID:       voiceSession.SessionID,
		ChannelID:       parsedRoom.ChannelID,
		TeamID:          voiceSession.TeamID,
		DurationSeconds: durationSeconds,
		Timestamp:       nowStr,
	}); err != nil {
		slog.Error("failed to publish SESSION_ENDED", "err", err)
	}
	return nil
}

func (s *WebhookService) findSession(ctx context.Context, roomName string) (*roomdomain.VoiceSession, error) {
	return s.repo.FindSessionByRoomName(ctx, roomName)
}
