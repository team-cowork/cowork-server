package webhook

import (
	"context"
	"time"

	livedomain "github.com/cowork/cowork-voice/internal/domain/live_room"
	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

type SessionRepository interface {
	FindSessionByRoomName(ctx context.Context, roomName string) (*roomdomain.VoiceSession, error)
	MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error)
	GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error)
	MarkParticipantLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error)
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
	CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error)
}

type LiveSessionRepository interface {
	FindSessionByRoomName(ctx context.Context, roomName string) (*livedomain.LiveSession, error)
	MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error)
	InsertViewer(ctx context.Context, v *livedomain.LiveViewer) error
	GetViewerJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error)
	MarkViewerLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error)
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
	CleanupOrphanViewers(ctx context.Context, sessionID string, now time.Time) (int64, error)
}

// LiveRoomController는 호스트 이탈 시 방을 폭파해 방송을 종료하는 데 쓰인다.
type LiveRoomController interface {
	DeleteRoom(ctx context.Context, roomName string) error
}

type WebhookEventType string

const (
	EventParticipantJoined WebhookEventType = "participant_joined"
	EventParticipantLeft   WebhookEventType = "participant_left"
	EventRoomFinished      WebhookEventType = "room_finished"
)
