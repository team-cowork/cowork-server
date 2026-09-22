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
	// FindActiveSession은 channelID의 현재 active 세션을 반환하거나, active 세션이 없으면
	// nil을 반환한다. 구현체는 caching 여부와 무관하게 반환값이 항상 MongoDB 기준으로
	// status가 active인 세션이도록 보장해야 한다(stale cache hit을 그대로 반환하지 않는다).
	FindActiveSession(ctx context.Context, channelID int64) (*VoiceSession, error)
	FindSessionByRoomName(ctx context.Context, roomName string) (*VoiceSession, error)
	// CreateSession은 새 세션을 만들고 (session, created=true)를 반환한다.
	// 동시 첫 입장 경쟁으로 이미 활성 세션이 존재하면 (기존 session, created=false)를 반환한다.
	CreateSession(ctx context.Context, channelID, teamID int64) (*VoiceSession, bool, error)
	GetSession(ctx context.Context, sessionID string) (*VoiceSession, error)
	// EndSession은 active 세션을 ended로 전환하고, 실제로 전환이 일어났으면 true를 반환한다.
	// 보상 정리 전용이며 외부로 알려야 하는 정상 종료에는 EndSessionAndEnqueue를 사용한다.
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
	// 아래 메서드는 authoritative 상태와 outbox event를 같은 Mongo document update에 기록한다.
	MarkSessionStartedAndEnqueue(ctx context.Context, sessionID string, startedAt time.Time, event any) (bool, error)
	RecordParticipantJoinedAndEnqueue(ctx context.Context, p *VoiceParticipant, occurrenceID string, event any) (bool, error)
	MarkParticipantLeftAndEnqueue(ctx context.Context, sessionID string, userID int64, occurrenceID string, now time.Time, event any) (bool, error)
	EndSessionAndEnqueue(ctx context.Context, sessionID string, endedAt time.Time, event any) (bool, error)
	CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error)
	GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64, occurrenceID string) (*time.Time, error)
}

type MembershipChecker interface {
	VerifyMembership(ctx context.Context, channelID, userID int64) (int64, error)
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
