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

	existing, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if existing != nil {
		return nil, apperr.Conflict("live already in progress")
	}

	liveSession, created, err := s.repo.CreateSession(ctx, channelID, teamID, userID)
	if err != nil {
		return nil, err
	}
	if !created {
		// 동시 start 경쟁에서 패배: voice와 달리 기존 세션에 합류하지 않고 충돌로 응답한다.
		return nil, apperr.Conflict("live already in progress")
	}
	if liveSession == nil {
		return nil, apperr.Internal("failed to create live session")
	}

	if err := s.livekit.CreateRoomIfNotExists(ctx, liveSession.RoomName); err != nil {
		if _, endErr := s.repo.EndSession(ctx, liveSession.SessionID, time.Now().UTC()); endErr != nil {
			slog.Error("failed to end live session", "err", endErr, "session_id", liveSession.SessionID)
		}
		return nil, err
	}

	token, err := s.livekit.GenerateToken(userID, liveSession.RoomName, true)
	if err != nil {
		return nil, err
	}

	return &StartResponse{
		Token:      token,
		LiveKitURL: s.livekitWsURL,
		SessionID:  liveSession.SessionID,
		RoomName:   liveSession.RoomName,
	}, nil
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
		// clock skew 등으로 now < joinedAt이면 음수가 될 수 있어 0으로 보정한다.
		if diff := now.Sub(*joinedAt).Seconds(); diff > 0 {
			durationSeconds = int64(diff)
		}
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

	lkParticipants, err := s.livekit.ListParticipants(ctx, liveSession.RoomName)
	if err != nil {
		return nil, err
	}

	hostIdentity := strconv.FormatInt(liveSession.HostUserID, 10)
	viewerCount := 0
	for _, p := range lkParticipants {
		if p.Identity != hostIdentity {
			viewerCount++
		}
	}

	return &StatusResponse{
		Live:        true,
		SessionID:   liveSession.SessionID,
		HostUserID:  liveSession.HostUserID,
		StartedAt:   liveSession.StartedAt.UTC().Format(time.RFC3339),
		ViewerCount: viewerCount,
	}, nil
}
