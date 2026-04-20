package webhook

import (
	"context"
	"time"

	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

type SessionRepository interface {
	FindSessionByRoomName(ctx context.Context, roomName string) (*roomdomain.VoiceSession, error)
	MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error)
	GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error)
	MarkParticipantLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error)
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) error
	CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error)
}

type WebhookEventType string

const (
	EventParticipantJoined WebhookEventType = "participant_joined"
	EventParticipantLeft   WebhookEventType = "participant_left"
	EventRoomFinished      WebhookEventType = "room_finished"
)
