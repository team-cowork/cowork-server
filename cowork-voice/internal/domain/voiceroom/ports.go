package voiceroom

import (
	"context"
	"time"

	"github.com/cowork/cowork-voice/internal/domain/permission"
)

type service interface {
	Join(ctx context.Context, channelID, userID int64) (*JoinResponse, error)
	Leave(ctx context.Context, channelID, userID int64) error
	GetParticipants(ctx context.Context, channelID, userID int64) (*ParticipantsResponse, error)
	GetSession(ctx context.Context, sessionID string, userID int64) (*SessionResponse, error)
}

type Repository interface {
	// FindActiveSession 메서드는 MongoDB에 저장된 상태가 active인 channelID의 세션을 반환한다.
	// active 세션이 없으면 nil을 반환한다.
	FindActiveSession(ctx context.Context, channelID int64) (*Session, error)
	// CreateSession 메서드는 새 세션을 만들고 (session, created=true)를 반환한다.
	// 동시 첫 입장 경쟁으로 이미 활성 세션이 존재하면 (기존 session, created=false)를 반환한다.
	CreateSession(ctx context.Context, channelID, teamID int64) (*Session, bool, error)
	GetSession(ctx context.Context, sessionID string) (*Session, error)
	// EndSession 메서드는 active 세션을 ended로 전환하고, 실제로 전환이 일어났으면 true를 반환한다.
	// 방 생성 또는 토큰 발급 실패를 정리할 때 사용한다.
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
	MarkParticipantLeftAndEnqueue(ctx context.Context, sessionID string, userID int64, occurrenceID string, now time.Time, event any) (bool, error)
	GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64, occurrenceID string) (*time.Time, error)
}

type ConnectPermissionChecker interface {
	RequireConnect(ctx context.Context, channelID, userID int64) (permission.Access, error)
}

type LiveKitParticipant struct {
	Identity string
	JoinedAt int64
}

type LiveKitRoom interface {
	// CreateRoomIfNotExists 메서드에서 maxParticipants 0은 제한 없음이다.
	CreateRoomIfNotExists(ctx context.Context, roomName string, maxParticipants uint32) error
	GenerateToken(userID int64, roomName string, speak, screenShare bool) (string, error)
	RemoveParticipant(ctx context.Context, roomName, identity string) error
	ListParticipants(ctx context.Context, roomName string) ([]LiveKitParticipant, error)
}
