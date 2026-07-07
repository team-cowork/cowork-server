package live

import (
	"context"
	"time"
)

type Service interface {
	Start(ctx context.Context, channelID, userID int64) (*StartResponse, error)
	Join(ctx context.Context, channelID, userID int64) (*JoinResponse, error)
	Leave(ctx context.Context, channelID, userID int64) error
	GetStatus(ctx context.Context, channelID, userID int64) (*StatusResponse, error)
}

type Repository interface {
	FindActiveSession(ctx context.Context, channelID int64) (*LiveSession, error)
	FindSessionByRoomName(ctx context.Context, roomName string) (*LiveSession, error)
	// CreateSession은 새 라이브 세션을 만들고 (session, created=true)를 반환한다.
	// 동시 start 경쟁으로 이미 활성 세션이 존재하면 (기존 session, created=false)를 반환한다.
	CreateSession(ctx context.Context, channelID, teamID, hostUserID int64) (*LiveSession, bool, error)
	// EndSession은 active 세션을 ended로 전환하고, 실제로 전환이 일어났으면 true를 반환한다.
	// 이미 종료된 세션(웹훅 재전송 등)이면 false → 호출 측이 LIVE_ENDED 중복 발행을 막는다.
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
	MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error)
	InsertViewer(ctx context.Context, v *LiveViewer) error
	MarkViewerLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error)
	CleanupOrphanViewers(ctx context.Context, sessionID string, now time.Time) (int64, error)
	GetViewerJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error)
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
	// GenerateToken은 isHost=true면 publish 권한(마이크·화면공유)을,
	// false면 subscribe 전용 권한을 가진 토큰을 발급한다.
	GenerateToken(userID int64, roomName string, isHost bool) (string, error)
	RemoveParticipant(ctx context.Context, roomName, identity string) error
	ListParticipants(ctx context.Context, roomName string) ([]LiveKitParticipant, error)
	// DeleteRoom은 방을 삭제해 전원을 퇴장시킨다(호스트 종료 시 사용). 없는 방이면 무시한다.
	DeleteRoom(ctx context.Context, roomName string) error
}
