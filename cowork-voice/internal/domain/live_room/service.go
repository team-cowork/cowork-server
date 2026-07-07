package live

import (
	"context"
	"log/slog"
	"strconv"
	"time"

	"github.com/cowork/cowork-voice/internal/apperr"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

type LiveService struct {
	repo         Repository
	membership   MembershipChecker
	livekit      LiveKitRoom
	publisher    EventPublisher
	livekitWsURL string
}

func NewLiveService(repo Repository, membership MembershipChecker, livekit LiveKitRoom, publisher EventPublisher, livekitWsURL string) *LiveService {
	return &LiveService{repo: repo, membership: membership, livekit: livekit, publisher: publisher, livekitWsURL: livekitWsURL}
}

func (s *LiveService) Start(ctx context.Context, channelID, userID int64) (*StartResponse, error) {
	teamID, err := s.membership.VerifyMembership(ctx, channelID, userID)
	if err != nil {
		return nil, err
	}

	// CreateSession의 duplicate-key 경쟁 재조회가 (nil, false, nil)을 반환할 수 있다:
	// 경쟁 상대의 세션이 우리가 재조회하기 전에 이미 종료된 극히 드문 경우다. 이때는
	// 채널이 실제로 비어 있으므로 오탐 409 대신 즉시 한 번 재시도한다.
	const maxAttempts = 2
	var liveSession *LiveSession
	for attempt := 0; attempt < maxAttempts; attempt++ {
		created, isNew, cerr := s.repo.CreateSession(ctx, channelID, teamID, userID)
		if cerr != nil {
			return nil, cerr
		}
		if isNew {
			liveSession = created
			break
		}
		if created != nil {
			return nil, apperr.Conflict("live already in progress")
		}
		// created == nil && !isNew: 경쟁 세션이 사라짐 → 재시도
	}
	if liveSession == nil {
		return nil, apperr.Internal("failed to create live session")
	}

	if err := s.livekit.CreateRoomIfNotExists(ctx, liveSession.RoomName); err != nil {
		s.compensateFailedStart(ctx, liveSession)
		return nil, err
	}

	token, err := s.livekit.GenerateToken(userID, liveSession.RoomName, true)
	if err != nil {
		s.compensateFailedStart(ctx, liveSession)
		return nil, err
	}

	return &StartResponse{
		Token:      token,
		LiveKitURL: s.livekitWsURL,
		SessionID:  liveSession.SessionID,
		RoomName:   liveSession.RoomName,
	}, nil
}

// compensateFailedStart는 세션 생성 이후 단계(LiveKit 방 생성·토큰 발급)가 실패했을 때
// 고아 상태로 남는 활성 세션을 종료해, 채널이 EmptyTimeout까지 묶이지 않게 한다.
// ctx는 클라이언트 취소·타임아웃으로 이미 취소됐을 수 있으므로, 보상 트랜잭션은
// 별도의 취소되지 않는 컨텍스트로 실행한다.
func (s *LiveService) compensateFailedStart(ctx context.Context, liveSession *LiveSession) {
	cleanupCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	if _, endErr := s.repo.EndSession(cleanupCtx, liveSession.SessionID, time.Now().UTC()); endErr != nil {
		slog.Error("failed to end live session", "err", endErr, "session_id", liveSession.SessionID)
	}
}

func (s *LiveService) Join(ctx context.Context, channelID, userID int64) (*JoinResponse, error) {
	if _, err := s.membership.VerifyMembership(ctx, channelID, userID); err != nil {
		return nil, err
	}

	liveSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if liveSession == nil {
		return nil, apperr.NotFound("active live not found")
	}

	isHost := userID == liveSession.HostUserID
	if !isHost {
		if err := s.repo.InsertViewer(ctx, &LiveViewer{
			SessionID: liveSession.SessionID,
			UserID:    userID,
			ChannelID: channelID,
			JoinedAt:  time.Now().UTC(),
		}); err != nil {
			return nil, err
		}
	}

	// 호스트 재입장이면 publish 권한 토큰을 재발급한다(시청자 행 미생성).
	token, err := s.livekit.GenerateToken(userID, liveSession.RoomName, isHost)
	if err != nil {
		return nil, err
	}

	return &JoinResponse{
		Token:      token,
		LiveKitURL: s.livekitWsURL,
		SessionID:  liveSession.SessionID,
		RoomName:   liveSession.RoomName,
		HostUserID: liveSession.HostUserID,
	}, nil
}

func (s *LiveService) Leave(ctx context.Context, channelID, userID int64) error {
	if _, err := s.membership.VerifyMembership(ctx, channelID, userID); err != nil {
		return err
	}

	liveSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return err
	}
	if liveSession == nil {
		return apperr.NotFound("active live not found")
	}

	if userID == liveSession.HostUserID {
		// 호스트 퇴장 = 방송 종료. DeleteRoom이 전원을 끊고, 세션 종료·이벤트 발행은
		// participant_left → room_finished 웹훅 경로가 처리한다(voice의 종료 철학과 동일).
		return s.livekit.DeleteRoom(ctx, liveSession.RoomName)
	}

	identity := strconv.FormatInt(userID, 10)
	if err := s.livekit.RemoveParticipant(ctx, liveSession.RoomName, identity); err != nil {
		return err
	}

	now := time.Now().UTC()
	joinedAt, err := s.repo.GetViewerJoinedAt(ctx, liveSession.SessionID, userID)
	if err != nil {
		slog.Warn("failed to get viewer joined_at", "err", err, "session_id", liveSession.SessionID)
	}

	// MarkViewerLeft가 dedup 게이트 역할을 한다. participant_left 웹훅과 경쟁하더라도
	// 먼저 left_at을 기록한 쪽만 VIEWER_LEFT를 발행해 이벤트 중복/유실을 방지한다.
	firstLeave, err := s.repo.MarkViewerLeft(ctx, liveSession.SessionID, userID, now)
	if err != nil {
		slog.Warn("failed to mark viewer left", "err", err, "session_id", liveSession.SessionID)
		return nil
	}
	if !firstLeave {
		return nil
	}

	var durationSeconds int64
	if joinedAt != nil {
		durationSeconds = DurationSecondsSince(now, *joinedAt)
	}
	if err := s.publisher.Publish(ctx, liveSession.SessionID, &kafkadomain.ViewerLeftEvent{
		EventType:       kafkadomain.EventViewerLeft,
		SessionID:       liveSession.SessionID,
		ChannelID:       channelID,
		TeamID:          liveSession.TeamID,
		UserID:          userID,
		DurationSeconds: durationSeconds,
		Timestamp:       now.Format(time.RFC3339),
	}); err != nil {
		slog.Error("failed to publish VIEWER_LEFT", "err", err, "session_id", liveSession.SessionID)
	}

	return nil
}

func (s *LiveService) GetStatus(ctx context.Context, channelID, userID int64) (*StatusResponse, error) {
	if _, err := s.membership.VerifyMembership(ctx, channelID, userID); err != nil {
		return nil, err
	}

	liveSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if liveSession == nil {
		return &StatusResponse{Live: false, ViewerCount: 0}, nil
	}

	viewerCount, err := s.repo.CountActiveViewers(ctx, liveSession.SessionID)
	if err != nil {
		return nil, err
	}

	return &StatusResponse{
		Live:        true,
		SessionID:   liveSession.SessionID,
		HostUserID:  liveSession.HostUserID,
		StartedAt:   liveSession.StartedAt.UTC().Format(time.RFC3339),
		ViewerCount: viewerCount,
	}, nil
}
