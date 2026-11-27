package liveroom

import (
	"context"
	"time"

	"github.com/cowork/cowork-voice/internal/domain/permission"
)

type service interface {
	Start(ctx context.Context, channelID, userID int64) (*StartResponse, error)
	Join(ctx context.Context, channelID, userID int64) (*JoinResponse, error)
	Leave(ctx context.Context, channelID, userID int64) error
	GetStatus(ctx context.Context, channelID, userID int64) (*StatusResponse, error)
}

type Repository interface {
	FindActiveSession(ctx context.Context, channelID int64) (*Session, error)
	// CreateSession 메서드는 새 라이브 세션을 만들고 (session, created=true)를 반환한다.
	// 동시 start 경쟁으로 이미 활성 세션이 존재하면 (기존 session, created=false)를 반환한다.
	CreateSession(ctx context.Context, channelID, teamID, hostUserID int64) (*Session, bool, error)
	// EndSession 메서드는 active 세션을 ended로 전환하고, 실제로 전환이 일어났으면 true를 반환한다.
	// 방 생성 또는 토큰 발급 실패를 정리할 때 사용한다.
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
	MarkViewerLeftAndEnqueue(ctx context.Context, sessionID string, userID int64, occurrenceID string, now time.Time, event any) (bool, error)
	GetViewerJoinedAt(ctx context.Context, sessionID string, userID int64, occurrenceID string) (*time.Time, error)
	// CountActiveViewers 메서드는 세션의 현재 활성(left_at=null) 시청자 수를 반환한다.
	CountActiveViewers(ctx context.Context, sessionID string) (int, error)
}

type ConnectPermissionChecker interface {
	RequireConnect(ctx context.Context, channelID, userID int64) (permission.Access, error)
}

type LiveKitRoom interface {
	CreateRoomIfNotExists(ctx context.Context, roomName string) error
	// GenerateToken restricts publishing to the currently allowed media sources.
	// Only the host publishes microphone and screen; screenShare also grants camera to any participant.
	GenerateToken(userID int64, roomName string, speak, screenShare, host bool) (string, error)
	RemoveParticipant(ctx context.Context, roomName, identity string) error
	// DeleteRoom 메서드는 방을 삭제해 전원을 퇴장시킨다(호스트 종료 시 사용). 없는 방이면 무시한다.
	DeleteRoom(ctx context.Context, roomName string) error
}
