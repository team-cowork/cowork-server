package live

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/livekit/protocol/livekit"
)

const (
	// startTimeout은 Start의 세션 저장과 방 생성 단계를 묶는다. 유예가 지난 생성 중 세션에
	// 방 생성이 아직 진행 중일 수 없게 해, 복구가 정상 시작을 끊지 않도록 한다.
	startTimeout = 15 * time.Second
	// startRecoveryGrace는 생성 중 세션을 중단으로 판정하기 전 기다리는 시간이다.
	// startTimeout과 레플리카 간 시계 차이를 넘도록 잡는다.
	startRecoveryGrace    = 60 * time.Second
	startRecoveryInterval = 30 * time.Second
)

// StartRecoveryRepository 인터페이스는 생성 중(active이고 started_event_sent_at 없음) 세션을 다룬다.
type StartRecoveryRepository interface {
	FindCreatingSessionsBefore(ctx context.Context, before time.Time) ([]LiveSession, error)
	// EndCreatingSession 메서드는 세션이 아직 생성 중일 때만 ended로 전환한다. 호스트가 그사이 입장했으면 no-op이다.
	EndCreatingSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error)
}

type RoomLister interface {
	ListRooms(context.Context, *livekit.ListRoomsRequest) (*livekit.ListRoomsResponse, error)
}

// StartRecovery 타입은 Start가 세션 저장과 방 생성 사이에서 중단돼 남은 활성 세션을 종료한다.
// 조건부 종료라 여러 레플리카에서 동시에 실행해도 안전하다.
type StartRecovery struct {
	repo  StartRecoveryRepository
	rooms RoomLister
	now   func() time.Time
}

func NewStartRecovery(repo StartRecoveryRepository, rooms RoomLister) *StartRecovery {
	return &StartRecovery{repo: repo, rooms: rooms, now: time.Now}
}

func (r *StartRecovery) Run(ctx context.Context) {
	ticker := time.NewTicker(startRecoveryInterval)
	defer ticker.Stop()
	for {
		if _, err := r.Recover(ctx); err != nil && ctx.Err() == nil {
			slog.Warn("Recover interrupted live starts", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// Recover 메서드는 유예가 지났고 LiveKit에 방이 없는 생성 중 세션을 종료하고, 종료한 수를 반환한다.
// LIVE_STARTED가 발행되지 않은 세션이므로 compensateFailedStart처럼 이벤트 없이 종료한다.
func (r *StartRecovery) Recover(ctx context.Context) (int, error) {
	now := r.now().UTC()
	findCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	sessions, err := r.repo.FindCreatingSessionsBefore(findCtx, now.Add(-startRecoveryGrace))
	cancel()
	if err != nil || len(sessions) == 0 {
		return 0, err
	}

	names := make([]string, 0, len(sessions))
	for _, session := range sessions {
		names = append(names, session.RoomName)
	}
	listCtx, listCancel := context.WithTimeout(ctx, 5*time.Second)
	rooms, err := r.rooms.ListRooms(listCtx, &livekit.ListRoomsRequest{Names: names})
	listCancel()
	if err != nil {
		// 조회 실패는 방 부재가 아니다. 이번 회차를 건너뛴다.
		return 0, fmt.Errorf("list live rooms: %w", err)
	}
	present := make(map[string]bool, len(rooms.GetRooms()))
	for _, room := range rooms.GetRooms() {
		present[room.GetName()] = true
	}

	ended := 0
	var failures []error
	for _, session := range sessions {
		if present[session.RoomName] {
			continue
		}
		endCtx, endCancel := context.WithTimeout(ctx, 5*time.Second)
		ok, err := r.repo.EndCreatingSession(endCtx, session.SessionID, now)
		endCancel()
		if err != nil {
			failures = append(failures, fmt.Errorf("session %s: %w", session.SessionID, err))
			continue
		}
		if ok {
			ended++
			slog.Info("ended interrupted live start", "session_id", session.SessionID, "channel_id", session.ChannelID)
		}
	}
	return ended, errors.Join(failures...)
}
