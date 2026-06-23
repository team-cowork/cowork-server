package room

import (
	"context"
	"time"
)

type Service interface {
	Join(ctx context.Context, channelID, userID int64) (*JoinResponse, error)
	Leave(ctx context.Context, channelID, userID int64) error
	GetParticipants(ctx context.Context, channelID, userID int64) (*ParticipantsResponse, error)
	GetSession(ctx context.Context, sessionID string, userID int64) (*SessionResponse, error)
}

type Repository interface {
	FindActiveSession(ctx context.Context, channelID int64) (*VoiceSession, error)
	FindSessionByRoomName(ctx context.Context, roomName string) (*VoiceSession, error)
	// CreateSession은 새 세션을 만들고 (session, created=true)를 반환한다.
	// 동시 첫 입장 경쟁으로 이미 활성 세션이 존재하면 (기존 session, created=false)를 반환한다.
	CreateSession(ctx context.Context, channelID, teamID int64) (*VoiceSession, bool, error)
	GetSession(ctx context.Context, sessionID string) (*VoiceSession, error)
	// EndSession은 active 세션을 ended로 전환하고, 실제로 전환이 일어났으면 true를 반환한다.
	// 이미 종료된 세션(웹훅 재전송 등)이면 false → 호출 측이 SESSION_ENDED 중복 발행을 막는다.
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
	MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error)
	InsertParticipant(ctx context.Context, p *VoiceParticipant) error
	MarkParticipantLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error)
	CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error)
	GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error)
}

type MembershipChecker interface {
	VerifyMembership(ctx context.Context, channelID, userID int64) (int64, error)
}

type EventPublisher interface {
	Publish(ctx context.Context, sessionID string, v any) error
}

type LiveKitParticipant struct {
	Identity string
	JoinedAt int64
}

type LiveKitRoom interface {
	CreateRoomIfNotExists(ctx context.Context, roomName string) error
	GenerateToken(userID int64, roomName string) (string, error)
	RemoveParticipant(ctx context.Context, roomName, identity string) error
	ListParticipants(ctx context.Context, roomName string) ([]LiveKitParticipant, error)
}
