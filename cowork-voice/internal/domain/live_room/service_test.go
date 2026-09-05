package live

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/cowork/cowork-voice/internal/apperr"
	kafka "github.com/cowork/cowork-voice/internal/infra/kafka"
)

func activeSession() *LiveSession {
	return &LiveSession{
		SessionID:  "session-1",
		ChannelID:  123,
		TeamID:     456,
		HostUserID: 42,
		RoomName:   "live-123-session-1",
		Status:     StatusActive,
		StartedAt:  time.Unix(1700000000, 0).UTC(),
	}
}

func TestLiveService(t *testing.T) {
	t.Run("Start - 활성 라이브가 없으면 세션을 만들고 호스트 토큰을 발급한다", func(t *testing.T) {
		t.Parallel()

		repo := &stubRepository{
			createSessionResult:  activeSession(),
			createSessionCreated: true,
		}
		livekit := &stubLiveKitRoom{token: "host-token"}
		svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, "wss://livekit.example")

		resp, err := svc.Start(context.Background(), 123, 42)
		if err != nil {
			t.Fatalf("Start() error = %v", err)
		}

		if repo.createSessionCalls != 1 {
			t.Fatalf("CreateSession() calls = %d, want 1", repo.createSessionCalls)
		}
		if repo.createSessionHostUserID != 42 {
			t.Fatalf("CreateSession() hostUserID = %d, want 42", repo.createSessionHostUserID)
		}
		if livekit.createdRoomName != "live-123-session-1" {
			t.Fatalf("CreateRoomIfNotExists() room = %q, want live-123-session-1", livekit.createdRoomName)
		}
		if !livekit.tokenIsHost {
			t.Fatal("GenerateToken() isHost = false, want true")
		}
		if resp.Token != "host-token" {
			t.Fatalf("response token = %q, want host-token", resp.Token)
		}
	})

	t.Run("Join - 라이브가 없으면 NotFound를 반환한다", func(t *testing.T) {
		t.Parallel()

		svc := NewLiveService(&stubRepository{}, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, "wss://livekit.example")

		_, err := svc.Join(context.Background(), 123, 99)
		if err == nil {
			t.Fatal("Join() error = nil, want not found")
		}
		appErr, ok := err.(*apperr.Error)
		if !ok {
			t.Fatalf("error type = %T, want *apperr.Error", err)
		}
		if appErr.HTTPStatus != 404 {
			t.Fatalf("HTTPStatus = %d, want 404", appErr.HTTPStatus)
		}
	})

	t.Run("Join - 시청자는 구독 전용 토큰을 받는다", func(t *testing.T) {
		t.Parallel()

		repo := &stubRepository{findActiveSessionResult: activeSession()}
		livekit := &stubLiveKitRoom{token: "viewer-token"}
		svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, "wss://livekit.example")

		resp, err := svc.Join(context.Background(), 123, 99)
		if err != nil {
			t.Fatalf("Join() error = %v", err)
		}

		if livekit.tokenIsHost {
			t.Fatal("GenerateToken() isHost = true, want false")
		}
		if resp.HostUserID != 42 {
			t.Fatalf("response host_user_id = %d, want 42", resp.HostUserID)
		}
	})

	t.Run("Join - 호스트 재입장이면 시청자 행 없이 호스트 토큰을 재발급한다", func(t *testing.T) {
		t.Parallel()

		repo := &stubRepository{findActiveSessionResult: activeSession()}
		livekit := &stubLiveKitRoom{token: "host-token"}
		svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, "wss://livekit.example")

		if _, err := svc.Join(context.Background(), 123, 42); err != nil {
			t.Fatalf("Join() error = %v", err)
		}

		if !livekit.tokenIsHost {
			t.Fatal("GenerateToken() isHost = false, want true")
		}
	})

	t.Run("Leave - 호스트가 나가면 방을 삭제하고 세션 종료는 웹훅에 맡긴다", func(t *testing.T) {
		t.Parallel()

		repo := &stubRepository{findActiveSessionResult: activeSession()}
		livekit := &stubLiveKitRoom{}
		svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, "wss://livekit.example")

		if err := svc.Leave(context.Background(), 123, 42); err != nil {
			t.Fatalf("Leave() error = %v", err)
		}

		if livekit.deletedRoomName != "live-123-session-1" {
			t.Fatalf("DeleteRoom() room = %q, want live-123-session-1", livekit.deletedRoomName)
		}
		if repo.endSessionCalls != 0 {
			t.Fatalf("EndSession() calls = %d, want 0 (webhook이 처리)", repo.endSessionCalls)
		}
		if len(repo.enqueuedEvents) != 0 {
			t.Fatalf("enqueued events = %d, want 0 (webhook이 처리)", len(repo.enqueuedEvents))
		}
	})

	t.Run("Leave - 시청자가 퇴장하면 퇴장 시각을 기록하고 VIEWER_LEFT를 발행한다", func(t *testing.T) {
		t.Parallel()

		joinedAt := time.Unix(1700000000, 0).UTC()
		repo := &stubRepository{
			findActiveSessionResult: activeSession(),
			getViewerJoinedAtValue:  &joinedAt,
			markViewerLeftFirst:     true,
		}
		svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, "wss://livekit.example")

		if err := svc.Leave(context.Background(), 123, 99); err != nil {
			t.Fatalf("Leave() error = %v", err)
		}

		if repo.markViewerLeftCalls != 1 {
			t.Fatalf("MarkViewerLeft() calls = %d, want 1", repo.markViewerLeftCalls)
		}
		if len(repo.enqueuedEvents) != 1 {
			t.Fatalf("enqueued events = %d, want 1 (VIEWER_LEFT)", len(repo.enqueuedEvents))
		}
		evt, ok := repo.enqueuedEvents[0].(*kafka.ViewerLeftEvent)
		if !ok {
			t.Fatalf("enqueued event type = %T, want *kafka.ViewerLeftEvent", repo.enqueuedEvents[0])
		}
		if evt.EventType != kafka.EventViewerLeft {
			t.Fatalf("event_type = %q, want %q", evt.EventType, kafka.EventViewerLeft)
		}
		if evt.SessionID != "session-1" || evt.UserID != 99 || evt.TeamID != 456 {
			t.Fatalf("event = %+v, want session-1/user 99/team 456", evt)
		}
	})

	t.Run("GetStatus - 라이브가 없으면 live=false를 반환한다", func(t *testing.T) {
		t.Parallel()

		svc := NewLiveService(&stubRepository{}, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, "wss://livekit.example")

		resp, err := svc.GetStatus(context.Background(), 123, 42)
		if err != nil {
			t.Fatalf("GetStatus() error = %v", err)
		}

		if resp.Live {
			t.Fatal("Live = true, want false")
		}
		if resp.ViewerCount != 0 {
			t.Fatalf("ViewerCount = %d, want 0", resp.ViewerCount)
		}
	})

	t.Run("GetStatus - 활성 시청자 수를 반환한다", func(t *testing.T) {
		t.Parallel()

		repo := &stubRepository{
			findActiveSessionResult:  activeSession(),
			countActiveViewersResult: 2,
		}
		svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, "wss://livekit.example")

		resp, err := svc.GetStatus(context.Background(), 123, 99)
		if err != nil {
			t.Fatalf("GetStatus() error = %v", err)
		}

		if !resp.Live {
			t.Fatal("Live = false, want true")
		}
		if resp.ViewerCount != 2 {
			t.Fatalf("ViewerCount = %d, want 2", resp.ViewerCount)
		}
		if resp.HostUserID != 42 {
			t.Fatalf("HostUserID = %d, want 42", resp.HostUserID)
		}
	})
}

type stubMembershipChecker struct {
	teamID int64
	err    error
}

func (s *stubMembershipChecker) VerifyMembership(_ context.Context, _, _ int64) (int64, error) {
	if s.err != nil {
		return 0, s.err
	}
	return s.teamID, nil
}

type stubLiveKitRoom struct {
	token           string
	createErr       error
	tokenErr        error
	removeErr       error
	listErr         error
	deleteErr       error
	participants    []LiveKitParticipant
	createdRoomName string
	deletedRoomName string
	tokenUserID     int64
	tokenRoomName   string
	tokenIsHost     bool
}

func (s *stubLiveKitRoom) CreateRoomIfNotExists(_ context.Context, roomName string) error {
	s.createdRoomName = roomName
	return s.createErr
}

func (s *stubLiveKitRoom) GenerateToken(userID int64, roomName string, isHost bool) (string, error) {
	s.tokenUserID = userID
	s.tokenRoomName = roomName
	s.tokenIsHost = isHost
	if s.tokenErr != nil {
		return "", s.tokenErr
	}
	return s.token, nil
}

func (s *stubLiveKitRoom) RemoveParticipant(_ context.Context, _, _ string) error {
	return s.removeErr
}

func (s *stubLiveKitRoom) ListParticipants(_ context.Context, _ string) ([]LiveKitParticipant, error) {
	if s.listErr != nil {
		return nil, s.listErr
	}
	return s.participants, nil
}

func (s *stubLiveKitRoom) DeleteRoom(_ context.Context, roomName string) error {
	s.deletedRoomName = roomName
	return s.deleteErr
}

type stubRepository struct {
	findActiveSessionResult  *LiveSession
	findActiveSessionErr     error
	createSessionResult      *LiveSession
	createSessionCreated     bool
	createSessionErr         error
	createSessionHostUserID  int64
	getViewerJoinedAtValue   *time.Time
	markViewerLeftFirst      bool
	enqueuedEvents           []any
	createSessionCalls       int
	markViewerLeftCalls      int
	endSessionCalls          int
	endSessionErr            error
	countActiveViewersResult int
	countActiveViewersErr    error
}

func (s *stubRepository) FindActiveSession(_ context.Context, _ int64) (*LiveSession, error) {
	return s.findActiveSessionResult, s.findActiveSessionErr
}

func (s *stubRepository) FindSessionByRoomName(_ context.Context, _ string) (*LiveSession, error) {
	return nil, errors.New("unexpected call")
}

func (s *stubRepository) CreateSession(_ context.Context, _, _, hostUserID int64) (*LiveSession, bool, error) {
	s.createSessionHostUserID = hostUserID
	s.createSessionCalls++
	return s.createSessionResult, s.createSessionCreated, s.createSessionErr
}

func (s *stubRepository) EndSession(_ context.Context, _ string, _ time.Time) (bool, error) {
	s.endSessionCalls++
	return s.endSessionErr == nil, s.endSessionErr
}

func (s *stubRepository) MarkSessionStartedAndEnqueue(_ context.Context, _ string, _ time.Time, _ any) (bool, error) {
	return false, errors.New("unexpected call")
}

func (s *stubRepository) RecordViewerJoinedAndEnqueue(
	_ context.Context,
	_ *LiveViewer,
	_ string,
	_ any,
) (bool, error) {
	return false, errors.New("unexpected call")
}

func (s *stubRepository) MarkViewerLeftAndEnqueue(
	_ context.Context,
	_ string,
	_ int64,
	_ string,
	_ time.Time,
	event any,
) (bool, error) {
	s.markViewerLeftCalls++
	if s.markViewerLeftFirst {
		s.enqueuedEvents = append(s.enqueuedEvents, event)
	}
	return s.markViewerLeftFirst, nil
}

func (s *stubRepository) EndSessionAndEnqueue(
	_ context.Context,
	_ string,
	_ time.Time,
	_ any,
	_ bool,
) (bool, error) {
	return false, errors.New("unexpected call")
}

func (s *stubRepository) CleanupOrphanViewers(_ context.Context, _ string, _ time.Time) (int64, error) {
	return 0, errors.New("unexpected call")
}

func (s *stubRepository) GetViewerJoinedAt(_ context.Context, _ string, _ int64, _ string) (*time.Time, error) {
	return s.getViewerJoinedAtValue, nil
}

func (s *stubRepository) CountActiveViewers(_ context.Context, _ string) (int, error) {
	return s.countActiveViewersResult, s.countActiveViewersErr
}
