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
	// 보상 정리 전용이며 외부로 알려야 하는 정상 종료에는 EndSessionAndEnqueue를 사용한다.
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
	// 아래 메서드는 authoritative 상태와 outbox event를 같은 Mongo document update에 기록한다.
	MarkSessionStartedAndEnqueue(ctx context.Context, sessionID string, startedAt time.Time, event any) (bool, error)
	RecordViewerJoinedAndEnqueue(ctx context.Context, v *LiveViewer, occurrenceID string, event any) (bool, error)
	MarkViewerLeftAndEnqueue(ctx context.Context, sessionID string, userID int64, occurrenceID string, now time.Time, event any) (bool, error)
	// enqueueOnlyIfStarted=true이면 실제 저장 문서에 started_event_sent_at이 있을 때만 event를 함께 적재한다.
	EndSessionAndEnqueue(ctx context.Context, sessionID string, endedAt time.Time, event any, enqueueOnlyIfStarted bool) (bool, error)
	CleanupOrphanViewers(ctx context.Context, sessionID string, now time.Time) (int64, error)
	GetViewerJoinedAt(ctx context.Context, sessionID string, userID int64, occurrenceID string) (*time.Time, error)
	// CountActiveViewers는 세션의 현재 활성(left_at=null) 시청자 수를 반환한다.
	CountActiveViewers(ctx context.Context, sessionID string) (int, error)
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
	// GenerateToken은 isHost=true면 publish 권한(마이크·화면공유)을,
	// false면 subscribe 전용 권한을 가진 토큰을 발급한다.
	GenerateToken(userID int64, roomName string, isHost bool) (string, error)
	RemoveParticipant(ctx context.Context, roomName, identity string) error
	ListParticipants(ctx context.Context, roomName string) ([]LiveKitParticipant, error)
	// DeleteRoom은 방을 삭제해 전원을 퇴장시킨다(호스트 종료 시 사용). 없는 방이면 무시한다.
	DeleteRoom(ctx context.Context, roomName string) error
}
