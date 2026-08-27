package webhook

import (
	"context"
	"testing"
	"time"

	livekit "github.com/livekit/protocol/livekit"

	livedomain "github.com/cowork/cowork-voice/internal/domain/live_room"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

func activeLiveSession() *livedomain.LiveSession {
	return &livedomain.LiveSession{
		SessionID:  "session-1",
		ChannelID:  123,
		TeamID:     456,
		HostUserID: 42,
		RoomName:   "live-123-session-1",
		Status:     livedomain.StatusActive,
		StartedAt:  time.Unix(1700000000, 0).UTC(),
	}
}

func TestLiveHandleEvent_호스트_첫_접속이면_LIVE_STARTED를_발행한다(t *testing.T) {
	t.Parallel()

	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: activeLiveSession(),
		markSessionStartedResult:    true,
	}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event:       "participant_joined",
		Room:        &livekit.Room{Name: "live-123-session-1"},
		Participant: &livekit.ParticipantInfo{Identity: "42"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if len(repo.messages) != 1 {
		t.Fatalf("enqueued messages = %d, want 1", len(repo.messages))
	}
	started, ok := repo.messages[0].event.(*kafkadomain.LiveStartedEvent)
	if !ok {
		t.Fatalf("event type = %T, want *LiveStartedEvent", repo.messages[0].event)
	}
	if started.HostUserID != 42 {
		t.Fatalf("host_user_id = %d, want 42", started.HostUserID)
	}
	if repo.recordViewerCalled {
		t.Fatal("RecordViewerJoinedAndEnqueue() was called for host")
	}
}

func TestLiveHandleEvent_호스트_재접속이면_LIVE_STARTED를_재발행하지_않는다(t *testing.T) {
	t.Parallel()

	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: activeLiveSession(),
		markSessionStartedResult:    false, // 게이트: 이미 시작 이벤트 발행됨
	}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event:       "participant_joined",
		Room:        &livekit.Room{Name: "live-123-session-1"},
		Participant: &livekit.ParticipantInfo{Identity: "42"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if len(repo.messages) != 0 {
		t.Fatalf("enqueued messages = %d, want 0", len(repo.messages))
	}
}

func TestLiveHandleEvent_시청자_접속이면_시청자_행을_만들고_VIEWER_JOINED를_발행한다(t *testing.T) {
	t.Parallel()

	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: activeLiveSession(),
	}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event:       "participant_joined",
		Room:        &livekit.Room{Name: "live-123-session-1"},
		Participant: &livekit.ParticipantInfo{Identity: "99"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if !repo.recordViewerCalled {
		t.Fatal("RecordViewerJoinedAndEnqueue() was not called")
	}
	if len(repo.messages) != 1 {
		t.Fatalf("enqueued messages = %d, want 1", len(repo.messages))
	}
	joined, ok := repo.messages[0].event.(*kafkadomain.ViewerJoinedEvent)
	if !ok {
		t.Fatalf("event type = %T, want *ViewerJoinedEvent", repo.messages[0].event)
	}
	if joined.UserID != 99 {
		t.Fatalf("user_id = %d, want 99", joined.UserID)
	}
}

func TestLiveHandleEvent_호스트가_나가면_방을_삭제한다(t *testing.T) {
	t.Parallel()

	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: activeLiveSession(),
	}
	room := &stubLiveRoomController{}
	svc := NewLiveWebhookService(repo, room)
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event:       "participant_left",
		Room:        &livekit.Room{Name: "live-123-session-1"},
		Participant: &livekit.ParticipantInfo{Identity: "42"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if room.deletedRoomName != "live-123-session-1" {
		t.Fatalf("DeleteRoom() room = %q, want live-123-session-1", room.deletedRoomName)
	}
	// 종료·이벤트 발행은 room_finished 웹훅 경로가 처리한다.
	if repo.endSessionCalled {
		t.Fatal("EndSession() was called, want webhook room_finished path")
	}
	if len(repo.messages) != 0 {
		t.Fatalf("enqueued messages = %d, want 0", len(repo.messages))
	}
}

func TestLiveHandleEvent_시청자_퇴장_첫_처리면_VIEWER_LEFT를_발행한다(t *testing.T) {
	t.Parallel()

	joinedAt := time.Unix(1700000000, 0).UTC()
	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: activeLiveSession(),
		getViewerJoinedAtResult:     &joinedAt,
		markViewerLeftResult:        true,
	}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event:       "participant_left",
		Room:        &livekit.Room{Name: "live-123-session-1"},
		Participant: &livekit.ParticipantInfo{Identity: "99"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if len(repo.messages) != 1 {
		t.Fatalf("enqueued messages = %d, want 1", len(repo.messages))
	}
	left, ok := repo.messages[0].event.(*kafkadomain.ViewerLeftEvent)
	if !ok {
		t.Fatalf("event type = %T, want *ViewerLeftEvent", repo.messages[0].event)
	}
	if left.DurationSeconds != 300 {
		t.Fatalf("duration_seconds = %d, want 300", left.DurationSeconds)
	}
}

func TestLiveHandleEvent_시청자_퇴장이_중복이면_발행하지_않는다(t *testing.T) {
	t.Parallel()

	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: activeLiveSession(),
		markViewerLeftResult:        false,
	}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event:       "participant_left",
		Room:        &livekit.Room{Name: "live-123-session-1"},
		Participant: &livekit.ParticipantInfo{Identity: "99"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if len(repo.messages) != 0 {
		t.Fatalf("enqueued messages = %d, want 0", len(repo.messages))
	}
}

func TestLiveHandleEvent_룸종료면_세션종료와_정리후_LIVE_ENDED를_발행한다(t *testing.T) {
	t.Parallel()

	startedAt := time.Unix(1700000000, 0).UTC()
	startedEventSentAt := startedAt
	sess := activeLiveSession()
	sess.StartedAt = startedAt
	sess.StartedEventSentAt = &startedEventSentAt
	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: sess,
		endSessionResult:            true,
		cleanupOrphanViewersResult:  2,
	}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000600, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event: "room_finished",
		Room:  &livekit.Room{Name: "live-123-session-1"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if !repo.cleanupCalled {
		t.Fatal("CleanupOrphanViewers() was not called")
	}
	if len(repo.messages) != 1 {
		t.Fatalf("enqueued messages = %d, want 1", len(repo.messages))
	}
	ended, ok := repo.messages[0].event.(*kafkadomain.LiveEndedEvent)
	if !ok {
		t.Fatalf("event type = %T, want *LiveEndedEvent", repo.messages[0].event)
	}
	if ended.DurationSeconds != 600 {
		t.Fatalf("duration_seconds = %d, want 600", ended.DurationSeconds)
	}
	if ended.HostUserID != 42 {
		t.Fatalf("host_user_id = %d, want 42", ended.HostUserID)
	}
}

func TestLiveHandleEvent_룸종료가_재전송이면_LIVE_ENDED를_중복발행하지_않는다(t *testing.T) {
	t.Parallel()

	sess := activeLiveSession()
	sess.Status = livedomain.StatusEnded
	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: sess,
		endSessionResult:            false, // 이미 종료됨 → 전환 없음
	}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000600, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event: "room_finished",
		Room:  &livekit.Room{Name: "live-123-session-1"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if len(repo.messages) != 0 {
		t.Fatalf("enqueued messages = %d, want 0 (already ended)", len(repo.messages))
	}
	if repo.cleanupCalled {
		t.Fatal("CleanupOrphanViewers should not run on redelivery")
	}
}

func TestLiveHandleEvent_호스트가_접속하지_않은_유령세션은_LIVE_ENDED를_발행하지_않는다(t *testing.T) {
	t.Parallel()

	// StartedEventSentAt == nil → LIVE_STARTED가 발행된 적 없는 세션
	repo := &stubLiveSessionRepository{
		findSessionByRoomNameResult: activeLiveSession(),
		endSessionResult:            true,
	}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000600, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event: "room_finished",
		Room:  &livekit.Room{Name: "live-123-session-1"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if len(repo.messages) != 0 {
		t.Fatalf("enqueued messages = %d, want 0 (ghost session)", len(repo.messages))
	}
}

func TestLiveHandleEvent_음성_룸_이름이면_무시한다(t *testing.T) {
	t.Parallel()

	repo := &stubLiveSessionRepository{}
	svc := NewLiveWebhookService(repo, &stubLiveRoomController{})
	svc.now = func() time.Time { return time.Unix(1700000300, 0).UTC() }

	if err := svc.HandleEvent(context.Background(), &livekit.WebhookEvent{
		Event:       "participant_joined",
		Room:        &livekit.Room{Name: "voice-123-session-1"},
		Participant: &livekit.ParticipantInfo{Identity: "42"},
	}); err != nil {
		t.Fatalf("HandleEvent() error = %v", err)
	}

	if len(repo.messages) != 0 {
		t.Fatalf("enqueued messages = %d, want 0", len(repo.messages))
	}
}

type stubLiveRoomController struct {
	deletedRoomName string
	deleteErr       error
}

func (s *stubLiveRoomController) DeleteRoom(_ context.Context, roomName string) error {
	s.deletedRoomName = roomName
	return s.deleteErr
}

type stubLiveSessionRepository struct {
	findSessionByRoomNameResult *livedomain.LiveSession
	findSessionByRoomNameErr    error
	markSessionStartedResult    bool
	markSessionStartedErr       error
	recordViewerErr             error
	recordViewerCalled          bool
	getViewerJoinedAtResult     *time.Time
	getViewerJoinedAtErr        error
	markViewerLeftResult        bool
	markViewerLeftErr           error
	cleanupOrphanViewersResult  int64
	cleanupOrphanViewersErr     error
	cleanupCalled               bool
	endSessionResult            bool
	endSessionErr               error
	endSessionCalled            bool
	messages                    []publishedMessage
}

func (s *stubLiveSessionRepository) FindSessionByRoomName(_ context.Context, _ string) (*livedomain.LiveSession, error) {
	return s.findSessionByRoomNameResult, s.findSessionByRoomNameErr
}

func (s *stubLiveSessionRepository) MarkSessionStartedAndEnqueue(
	_ context.Context,
	sessionID string,
	_ time.Time,
	event any,
) (bool, error) {
	if s.markSessionStartedResult && s.markSessionStartedErr == nil {
		s.messages = append(s.messages, publishedMessage{sessionID: sessionID, event: event})
	}
	return s.markSessionStartedResult, s.markSessionStartedErr
}

func (s *stubLiveSessionRepository) RecordViewerJoinedAndEnqueue(
	_ context.Context,
	viewer *livedomain.LiveViewer,
	_ string,
	event any,
) (bool, error) {
	s.recordViewerCalled = true
	if s.recordViewerErr == nil {
		s.messages = append(s.messages, publishedMessage{sessionID: viewer.SessionID, event: event})
	}
	return s.recordViewerErr == nil, s.recordViewerErr
}

func (s *stubLiveSessionRepository) GetViewerJoinedAt(
	_ context.Context,
	_ string,
	_ int64,
	_ string,
) (*time.Time, error) {
	return s.getViewerJoinedAtResult, s.getViewerJoinedAtErr
}

func (s *stubLiveSessionRepository) MarkViewerLeftAndEnqueue(
	_ context.Context,
	sessionID string,
	_ int64,
	_ string,
	_ time.Time,
	event any,
) (bool, error) {
	if s.markViewerLeftResult && s.markViewerLeftErr == nil {
		s.messages = append(s.messages, publishedMessage{sessionID: sessionID, event: event})
	}
	return s.markViewerLeftResult, s.markViewerLeftErr
}

func (s *stubLiveSessionRepository) EndSessionAndEnqueue(
	_ context.Context,
	sessionID string,
	_ time.Time,
	event any,
	enqueueOnlyIfStarted bool,
) (bool, error) {
	s.endSessionCalled = true
	started := s.findSessionByRoomNameResult != nil && s.findSessionByRoomNameResult.StartedEventSentAt != nil
	if s.endSessionResult && s.endSessionErr == nil && (!enqueueOnlyIfStarted || started) {
		s.messages = append(s.messages, publishedMessage{sessionID: sessionID, event: event})
	}
	return s.endSessionResult, s.endSessionErr
}

func (s *stubLiveSessionRepository) CleanupOrphanViewers(_ context.Context, _ string, _ time.Time) (int64, error) {
	s.cleanupCalled = true
	return s.cleanupOrphanViewersResult, s.cleanupOrphanViewersErr
}
