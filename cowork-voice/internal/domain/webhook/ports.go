package webhook

import (
	"context"
	"time"

	livedomain "github.com/cowork/cowork-voice/internal/domain/live_room"
	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

type SessionRepository interface {
	FindSessionByRoomName(ctx context.Context, roomName string) (*roomdomain.VoiceSession, error)
	MarkSessionStartedAndEnqueue(ctx context.Context, sessionID string, startedAt time.Time, event any) (bool, error)
	RecordParticipantJoinedAndEnqueue(ctx context.Context, participant *roomdomain.VoiceParticipant, occurrenceID string, event any) (bool, error)
	GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64, occurrenceID string) (*time.Time, error)
	MarkParticipantLeftAndEnqueue(ctx context.Context, sessionID string, userID int64, occurrenceID string, now time.Time, event any) (bool, error)
	EndSessionAndEnqueue(ctx context.Context, sessionID string, endedAt time.Time, event any) (bool, error)
	CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error)
}

type LiveSessionRepository interface {
	FindSessionByRoomName(ctx context.Context, roomName string) (*livedomain.LiveSession, error)
	MarkSessionStartedAndEnqueue(ctx context.Context, sessionID string, startedAt time.Time, event any) (bool, error)
	RecordViewerJoinedAndEnqueue(ctx context.Context, viewer *livedomain.LiveViewer, occurrenceID string, event any) (bool, error)
	GetViewerJoinedAt(ctx context.Context, sessionID string, userID int64, occurrenceID string) (*time.Time, error)
	MarkViewerLeftAndEnqueue(ctx context.Context, sessionID string, userID int64, occurrenceID string, now time.Time, event any) (bool, error)
	EndSessionAndEnqueue(ctx context.Context, sessionID string, endedAt time.Time, event any, enqueueOnlyIfStarted bool) (bool, error)
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
