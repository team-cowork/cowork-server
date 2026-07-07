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

func TestStart_활성_라이브가_없으면_세션을_만들고_호스트_토큰을_발급한다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{
		createSessionResult:  activeSession(),
		createSessionCreated: true,
	}
	livekit := &stubLiveKitRoom{token: "host-token"}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, &stubPublisher{}, "wss://livekit.example")

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
}

func TestStart_이미_라이브_중이면_Conflict를_반환한다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{findActiveSessionResult: activeSession()}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, &stubPublisher{}, "wss://livekit.example")

	_, err := svc.Start(context.Background(), 123, 99)
	assertConflict(t, err)

	if repo.createSessionCalls != 0 {
		t.Fatalf("CreateSession() calls = %d, want 0", repo.createSessionCalls)
	}
}

func TestStart_동시_start_경쟁에서_지면_Conflict를_반환한다(t *testing.T) {
	t.Parallel()

	// CreateSession이 duplicate key 경쟁으로 다른 호스트가 만든 세션을 created=false로 반환.
	repo := &stubRepository{
		createSessionResult:  activeSession(),
		createSessionCreated: false,
	}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, &stubPublisher{}, "wss://livekit.example")

	_, err := svc.Start(context.Background(), 123, 99)
	assertConflict(t, err)

	// 다른 호스트의 세션을 종료시켜서는 안 된다.
	if repo.endSessionCalls != 0 {
		t.Fatalf("EndSession() calls = %d, want 0", repo.endSessionCalls)
	}
}

func TestStart_LiveKit방_생성_실패시_생성된_세션을_정리한다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{
		createSessionResult:  activeSession(),
		createSessionCreated: true,
	}
	lk := &stubLiveKitRoom{createErr: errors.New("livekit unavailable")}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, lk, &stubPublisher{}, "wss://livekit.example")

	if _, err := svc.Start(context.Background(), 123, 42); err == nil {
		t.Fatal("Start() error = nil, want error")
	}

	if repo.endSessionCalls != 1 {
		t.Fatalf("EndSession() calls = %d, want 1", repo.endSessionCalls)
	}
}

func TestJoin_라이브가_없으면_NotFound를_반환한다(t *testing.T) {
	t.Parallel()

	svc := NewLiveService(&stubRepository{}, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, &stubPublisher{}, "wss://livekit.example")

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
}

func TestJoin_시청자는_시청자_행을_만들고_구독_전용_토큰을_받는다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{findActiveSessionResult: activeSession()}
	livekit := &stubLiveKitRoom{token: "viewer-token"}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, &stubPublisher{}, "wss://livekit.example")

	resp, err := svc.Join(context.Background(), 123, 99)
	if err != nil {
		t.Fatalf("Join() error = %v", err)
	}

	if repo.insertViewer == nil {
		t.Fatal("InsertViewer() was not called")
	}
	if repo.insertViewer.UserID != 99 {
		t.Fatalf("viewer user_id = %d, want 99", repo.insertViewer.UserID)
	}
	if livekit.tokenIsHost {
		t.Fatal("GenerateToken() isHost = true, want false")
	}
	if resp.HostUserID != 42 {
		t.Fatalf("response host_user_id = %d, want 42", resp.HostUserID)
	}
}

func TestJoin_호스트_재입장이면_시청자_행_없이_호스트_토큰을_재발급한다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{findActiveSessionResult: activeSession()}
	livekit := &stubLiveKitRoom{token: "host-token"}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, &stubPublisher{}, "wss://livekit.example")

	if _, err := svc.Join(context.Background(), 123, 42); err != nil {
		t.Fatalf("Join() error = %v", err)
	}

	if repo.insertViewer != nil {
		t.Fatal("InsertViewer() was called, want no call for host")
	}
	if !livekit.tokenIsHost {
		t.Fatal("GenerateToken() isHost = false, want true")
	}
}

func TestLeave_호스트가_나가면_방을_삭제하고_세션_종료는_웹훅에_맡긴다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{findActiveSessionResult: activeSession()}
	livekit := &stubLiveKitRoom{}
	publisher := &stubPublisher{}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, publisher, "wss://livekit.example")

	if err := svc.Leave(context.Background(), 123, 42); err != nil {
		t.Fatalf("Leave() error = %v", err)
	}

	if livekit.deletedRoomName != "live-123-session-1" {
		t.Fatalf("DeleteRoom() room = %q, want live-123-session-1", livekit.deletedRoomName)
	}
	if repo.endSessionCalls != 0 {
		t.Fatalf("EndSession() calls = %d, want 0 (webhook이 처리)", repo.endSessionCalls)
	}
	if len(publisher.published) != 0 {
		t.Fatalf("published events = %d, want 0 (webhook이 발행)", len(publisher.published))
	}
}

func TestLeave_시청자_퇴장시_DB에_표시하고_VIEWER_LEFT를_발행한다(t *testing.T) {
	t.Parallel()

	joinedAt := time.Unix(1700000000, 0).UTC()
	repo := &stubRepository{
		findActiveSessionResult: activeSession(),
		getViewerJoinedAtValue:  &joinedAt,
		markViewerLeftFirst:     true,
	}
	publisher := &stubPublisher{}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, publisher, "wss://livekit.example")

	if err := svc.Leave(context.Background(), 123, 99); err != nil {
		t.Fatalf("Leave() error = %v", err)
	}

	if repo.markViewerLeftCalls != 1 {
		t.Fatalf("MarkViewerLeft() calls = %d, want 1", repo.markViewerLeftCalls)
	}
	if len(publisher.published) != 1 {
		t.Fatalf("published events = %d, want 1 (VIEWER_LEFT)", len(publisher.published))
	}
	evt, ok := publisher.published[0].(*kafka.ViewerLeftEvent)
	if !ok {
		t.Fatalf("published event type = %T, want *kafka.ViewerLeftEvent", publisher.published[0])
	}
	if evt.EventType != kafka.EventViewerLeft {
		t.Fatalf("event_type = %q, want %q", evt.EventType, kafka.EventViewerLeft)
	}
	if evt.SessionID != "session-1" || evt.UserID != 99 || evt.TeamID != 456 {
		t.Fatalf("event = %+v, want session-1/user 99/team 456", evt)
	}
}

func TestLeave_웹훅이_먼저_표시했으면_중복_발행하지_않는다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{
		findActiveSessionResult: activeSession(),
		markViewerLeftFirst:     false,
	}
	publisher := &stubPublisher{}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, publisher, "wss://livekit.example")

	if err := svc.Leave(context.Background(), 123, 99); err != nil {
		t.Fatalf("Leave() error = %v", err)
	}

	if len(publisher.published) != 0 {
		t.Fatalf("published events = %d, want 0 (already marked by webhook)", len(publisher.published))
	}
}

func TestGetStatus_라이브가_없으면_live_false를_반환한다(t *testing.T) {
	t.Parallel()

	svc := NewLiveService(&stubRepository{}, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, &stubPublisher{}, "wss://livekit.example")

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
}

func TestGetStatus_호스트를_제외한_시청자_수를_센다(t *testing.T) {
	t.Parallel()

	repo := &stubRepository{findActiveSessionResult: activeSession()}
	livekit := &stubLiveKitRoom{
		participants: []LiveKitParticipant{
			{Identity: "42", JoinedAt: 1700000100}, // 호스트
			{Identity: "99", JoinedAt: 1700000200},
			{Identity: "100", JoinedAt: 1700000300},
		},
	}
	svc := NewLiveService(repo, &stubMembershipChecker{teamID: 456}, livekit, &stubPublisher{}, "wss://livekit.example")

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
}

func assertConflict(t *testing.T, err error) {
	t.Helper()
	if err == nil {
		t.Fatal("error = nil, want conflict")
	}
	appErr, ok := err.(*apperr.Error)
	if !ok {
		t.Fatalf("error type = %T, want *apperr.Error", err)
	}
	if appErr.HTTPStatus != 409 {
		t.Fatalf("HTTPStatus = %d, want 409", appErr.HTTPStatus)
	}
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
	findActiveSessionResult *LiveSession
	findActiveSessionErr    error
	createSessionResult     *LiveSession
	createSessionCreated    bool
	createSessionErr        error
	createSessionHostUserID int64
	insertViewerErr         error
	insertViewer            *LiveViewer
	getViewerJoinedAtValue  *time.Time
	markViewerLeftFirst     bool
	createSessionCalls      int
	markViewerLeftCalls     int
	endSessionCalls         int
	endSessionErr           error
}

func (s *stubRepository) FindActiveSession(_ context.Context, _ int64) (*LiveSession, error) {
	return s.findActiveSessionResult, s.findActiveSessionErr
}

func (s *stubRepository) FindSessionByRoomName(_ context.Context, _ string) (*LiveSession, error) {
	return nil, errors.New("unexpected call")
}

func (s *stubRepository) CreateSession(_ context.Context, _, _, hostUserID int64) (*LiveSession, bool, error) {
	s.createSessionCalls++
	s.createSessionHostUserID = hostUserID
	return s.createSessionResult, s.createSessionCreated, s.createSessionErr
}

func (s *stubRepository) EndSession(_ context.Context, _ string, _ time.Time) (bool, error) {
	s.endSessionCalls++
	return s.endSessionErr == nil, s.endSessionErr
}

func (s *stubRepository) MarkSessionStarted(_ context.Context, _ string, _ time.Time) (bool, error) {
	return false, errors.New("unexpected call")
}

func (s *stubRepository) InsertViewer(_ context.Context, v *LiveViewer) error {
	s.insertViewer = v
	return s.insertViewerErr
}

func (s *stubRepository) MarkViewerLeft(_ context.Context, _ string, _ int64, _ time.Time) (bool, error) {
	s.markViewerLeftCalls++
	return s.markViewerLeftFirst, nil
}

func (s *stubRepository) CleanupOrphanViewers(_ context.Context, _ string, _ time.Time) (int64, error) {
	return 0, errors.New("unexpected call")
}

func (s *stubRepository) GetViewerJoinedAt(_ context.Context, _ string, _ int64) (*time.Time, error) {
	return s.getViewerJoinedAtValue, nil
}

type stubPublisher struct {
	published []any
}

func (s *stubPublisher) Publish(_ context.Context, _ string, v any) error {
	s.published = append(s.published, v)
	return nil
}
