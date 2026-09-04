package room

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/cowork/cowork-voice/internal/apperr"
	kafka "github.com/cowork/cowork-voice/internal/infra/kafka"
)

func TestVoiceRoomService(t *testing.T) {
	t.Run("Join - 활성 세션이 없으면 생성하고 참가자를 추가한다", func(t *testing.T) {
		t.Parallel()

		repo := &stubRepository{
			createSessionResult: &VoiceSession{
				SessionID: "session-1",
				ChannelID: 123,
				TeamID:    456,
				RoomName:  "voice-123-session-1",
				Status:    StatusActive,
				StartedAt: time.Unix(1700000000, 0).UTC(),
			},
		}
		membership := &stubMembershipChecker{teamID: 456}
		livekit := &stubLiveKitRoom{token: "issued-token"}
		svc := NewRoomService(repo, membership, livekit, "wss://livekit.example")

		resp, err := svc.Join(context.Background(), 123, 42)
		if err != nil {
			t.Fatalf("Join() error = %v", err)
		}

		if repo.createSessionCalls != 1 {
			t.Fatalf("CreateSession() calls = %d, want 1", repo.createSessionCalls)
		}
		if livekit.createdRoomName != "voice-123-session-1" {
			t.Fatalf("CreateRoomIfNotExists() room = %q, want voice-123-session-1", livekit.createdRoomName)
		}
		if livekit.tokenUserID != 42 {
			t.Fatalf("GenerateToken() userID = %d, want 42", livekit.tokenUserID)
		}
		if resp.Token != "issued-token" {
			t.Fatalf("response token = %q, want issued-token", resp.Token)
		}
		if resp.LiveKitURL != "wss://livekit.example" {
			t.Fatalf("response livekit_url = %q, want wss://livekit.example", resp.LiveKitURL)
		}
	})

	t.Run("Join - 활성 세션이 있으면 재사용한다", func(t *testing.T) {
		t.Parallel()

		repo := &stubRepository{
			findActiveSessionResult: &VoiceSession{
				SessionID: "session-1",
				ChannelID: 123,
				TeamID:    456,
				RoomName:  "voice-123-session-1",
				Status:    StatusActive,
				StartedAt: time.Unix(1700000000, 0).UTC(),
			},
		}
		svc := NewRoomService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{token: "issued-token"}, "wss://livekit.example")

		_, err := svc.Join(context.Background(), 123, 42)
		if err != nil {
			t.Fatalf("Join() error = %v", err)
		}

		if repo.createSessionCalls != 0 {
			t.Fatalf("CreateSession() calls = %d, want 0", repo.createSessionCalls)
		}
	})

	t.Run("GetParticipants - 유효하지 않은 아이덴티티는 제외한다", func(t *testing.T) {
		t.Parallel()

		repo := &stubRepository{
			findActiveSessionResult: &VoiceSession{
				SessionID: "session-1",
				ChannelID: 123,
				RoomName:  "voice-123-session-1",
				Status:    StatusActive,
				StartedAt: time.Unix(1700000000, 0).UTC(),
			},
		}
		livekit := &stubLiveKitRoom{
			participants: []LiveKitParticipant{
				{Identity: "42", JoinedAt: 1700000100},
				{Identity: "not-a-number", JoinedAt: 1700000200},
				{Identity: "84", JoinedAt: 1700000300},
			},
		}
		svc := NewRoomService(repo, &stubMembershipChecker{teamID: 456}, livekit, "wss://livekit.example")

		resp, err := svc.GetParticipants(context.Background(), 123, 42)
		if err != nil {
			t.Fatalf("GetParticipants() error = %v", err)
		}

		if len(resp.Participants) != 2 {
			t.Fatalf("participants len = %d, want 2", len(resp.Participants))
		}
		if resp.Participants[0].UserID != 42 {
			t.Fatalf("participants[0].user_id = %d, want 42", resp.Participants[0].UserID)
		}
		if resp.Participants[1].UserID != 84 {
			t.Fatalf("participants[1].user_id = %d, want 84", resp.Participants[1].UserID)
		}
	})

	t.Run("GetParticipants - 활성 세션이 없으면 빈 목록을 반환한다", func(t *testing.T) {
		t.Parallel()

		svc := NewRoomService(&stubRepository{}, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, "wss://livekit.example")

		resp, err := svc.GetParticipants(context.Background(), 123, 42)
		if err != nil {
			t.Fatalf("GetParticipants() error = %v", err)
		}

		if resp.ChannelID != 123 {
			t.Fatalf("channel_id = %d, want 123", resp.ChannelID)
		}
		if len(resp.Participants) != 0 {
			t.Fatalf("participants len = %d, want 0", len(resp.Participants))
		}
	})

	t.Run("GetSession - 세션이 없으면 NotFound를 반환한다", func(t *testing.T) {
		t.Parallel()

		svc := NewRoomService(&stubRepository{}, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, "wss://livekit.example")

		_, err := svc.GetSession(context.Background(), "missing-session", 42)
		if err == nil {
			t.Fatal("GetSession() error = nil, want not found")
		}

		appErr, ok := err.(*apperr.Error)
		if !ok {
			t.Fatalf("error type = %T, want *apperr.Error", err)
		}
		if appErr.HTTPStatus != 404 {
			t.Fatalf("HTTPStatus = %d, want 404", appErr.HTTPStatus)
		}
	})

	t.Run("GetSession - 종료 시각이 있으면 RFC3339로 반환한다", func(t *testing.T) {
		t.Parallel()

		endedAt := time.Unix(1700000500, 0).UTC()
		repo := &stubRepository{
			getSessionResult: &VoiceSession{
				SessionID: "session-1",
				ChannelID: 123,
				TeamID:    456,
				Status:    StatusEnded,
				StartedAt: time.Unix(1700000000, 0).UTC(),
				EndedAt:   &endedAt,
			},
		}
		svc := NewRoomService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, "wss://livekit.example")

		resp, err := svc.GetSession(context.Background(), "session-1", 42)
		if err != nil {
			t.Fatalf("GetSession() error = %v", err)
		}

		if resp.EndedAt == nil {
			t.Fatal("EndedAt = nil, want value")
		}
		if *resp.EndedAt != endedAt.Format(time.RFC3339) {
			t.Fatalf("EndedAt = %q, want %q", *resp.EndedAt, endedAt.Format(time.RFC3339))
		}
	})

	t.Run("Leave - 퇴장하면 퇴장 시각을 기록하고 USER_LEFT를 발행한다", func(t *testing.T) {
		t.Parallel()

		joinedAt := time.Unix(1700000000, 0).UTC()
		repo := &stubRepository{
			findActiveSessionResult: &VoiceSession{
				SessionID: "session-1",
				ChannelID: 123,
				TeamID:    456,
				RoomName:  "voice-123-session-1",
				Status:    StatusActive,
				StartedAt: joinedAt,
			},
			getParticipantJoinedAtValue: &joinedAt,
			markParticipantLeftFirst:    true,
		}
		svc := NewRoomService(repo, &stubMembershipChecker{teamID: 456}, &stubLiveKitRoom{}, "wss://livekit.example")

		if err := svc.Leave(context.Background(), 123, 42); err != nil {
			t.Fatalf("Leave() error = %v", err)
		}

		if repo.markParticipantLeftCalls != 1 {
			t.Fatalf("MarkParticipantLeft() calls = %d, want 1", repo.markParticipantLeftCalls)
		}
		if len(repo.enqueuedEvents) != 1 {
			t.Fatalf("enqueued events = %d, want 1 (USER_LEFT)", len(repo.enqueuedEvents))
		}
		evt, ok := repo.enqueuedEvents[0].(*kafka.UserLeftEvent)
		if !ok {
			t.Fatalf("enqueued event type = %T, want *kafka.UserLeftEvent", repo.enqueuedEvents[0])
		}
		if evt.EventType != kafka.EventUserLeft {
			t.Fatalf("event_type = %q, want %q", evt.EventType, kafka.EventUserLeft)
		}
		if evt.SessionID != "session-1" || evt.UserID != 42 || evt.TeamID != 456 {
			t.Fatalf("event = %+v, want session-1/user 42/team 456", evt)
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
	participants    []LiveKitParticipant
	createdRoomName string
	tokenUserID     int64
	tokenRoomName   string
}

func (s *stubLiveKitRoom) CreateRoomIfNotExists(_ context.Context, roomName string) error {
	s.createdRoomName = roomName
	return s.createErr
}

func (s *stubLiveKitRoom) GenerateToken(userID int64, roomName string) (string, error) {
	s.tokenUserID = userID
	s.tokenRoomName = roomName
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

type stubRepository struct {
	findActiveSessionResult     *VoiceSession
	findActiveSessionErr        error
	createSessionResult         *VoiceSession
	createSessionCreated        bool
	createSessionErr            error
	getSessionResult            *VoiceSession
	getSessionErr               error
	getParticipantJoinedAtValue *time.Time
	markParticipantLeftFirst    bool
	enqueuedEvents              []any
	createSessionCalls          int
	markParticipantLeftCalls    int
	endSessionCalls             int
	endSessionErr               error
}

func (s *stubRepository) FindActiveSession(_ context.Context, _ int64) (*VoiceSession, error) {
	return s.findActiveSessionResult, s.findActiveSessionErr
}

func (s *stubRepository) FindSessionByRoomName(_ context.Context, _ string) (*VoiceSession, error) {
	return nil, errors.New("unexpected call")
}

func (s *stubRepository) CreateSession(_ context.Context, _, _ int64) (*VoiceSession, bool, error) {
	s.createSessionCalls++
	return s.createSessionResult, s.createSessionCreated, s.createSessionErr
}

func (s *stubRepository) GetSession(_ context.Context, _ string) (*VoiceSession, error) {
	return s.getSessionResult, s.getSessionErr
}

func (s *stubRepository) EndSession(_ context.Context, _ string, _ time.Time) (bool, error) {
	s.endSessionCalls++
	return s.endSessionErr == nil, s.endSessionErr
}

func (s *stubRepository) MarkSessionStartedAndEnqueue(_ context.Context, _ string, _ time.Time, _ any) (bool, error) {
	return false, errors.New("unexpected call")
}

func (s *stubRepository) RecordParticipantJoinedAndEnqueue(
	_ context.Context,
	_ *VoiceParticipant,
	_ string,
	_ any,
) (bool, error) {
	return false, errors.New("unexpected call")
}

func (s *stubRepository) MarkParticipantLeftAndEnqueue(
	_ context.Context,
	_ string,
	_ int64,
	_ string,
	_ time.Time,
	event any,
) (bool, error) {
	s.markParticipantLeftCalls++
	if s.markParticipantLeftFirst {
		s.enqueuedEvents = append(s.enqueuedEvents, event)
	}
	return s.markParticipantLeftFirst, nil
}

func (s *stubRepository) EndSessionAndEnqueue(
	_ context.Context,
	_ string,
	_ time.Time,
	_ any,
) (bool, error) {
	return false, errors.New("unexpected call")
}

func (s *stubRepository) CleanupOrphanParticipants(_ context.Context, _ string, _ time.Time) (int64, error) {
	return 0, errors.New("unexpected call")
}

func (s *stubRepository) GetParticipantJoinedAt(_ context.Context, _ string, _ int64, _ string) (*time.Time, error) {
	return s.getParticipantJoinedAtValue, nil
}
