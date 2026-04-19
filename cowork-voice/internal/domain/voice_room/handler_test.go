package room

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/cowork/cowork-voice/internal/apperr"
	"github.com/cowork/cowork-voice/internal/middleware"
)

type stubService struct {
	joinResp *JoinResponse
	joinErr  error
	leaveErr error
	partResp *ParticipantsResponse
	partErr  error
	sessResp *SessionResponse
	sessErr  error

	gotChannelID int64
	gotUserID    int64
	gotSessionID string
}

func (s *stubService) Join(_ context.Context, channelID, userID int64) (*JoinResponse, error) {
	s.gotChannelID = channelID
	s.gotUserID = userID
	return s.joinResp, s.joinErr
}

func (s *stubService) Leave(_ context.Context, channelID, userID int64) error {
	s.gotChannelID = channelID
	s.gotUserID = userID
	return s.leaveErr
}

func (s *stubService) GetParticipants(_ context.Context, channelID, userID int64) (*ParticipantsResponse, error) {
	s.gotChannelID = channelID
	s.gotUserID = userID
	return s.partResp, s.partErr
}

func (s *stubService) GetSession(_ context.Context, sessionID string, userID int64) (*SessionResponse, error) {
	s.gotSessionID = sessionID
	s.gotUserID = userID
	return s.sessResp, s.sessErr
}

func TestJoin_InvalidChannelID_ReturnsBadRequest(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodPost, "/voice/channels/not-a-number/join", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "not-a-number"))
	req = req.WithContext(withUserID(req.Context(), 42))
	rec := httptest.NewRecorder()

	handler := NewHandler(nil)
	handler.Join(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusBadRequest)
	}

	var body map[string]any
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if body["error"] != "BAD_REQUEST" {
		t.Fatalf("error body = %v, want BAD_REQUEST", body["error"])
	}
}

func TestJoin_UserID없으면_401을_반환한다(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodPost, "/voice/channels/1/join", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "1"))
	rec := httptest.NewRecorder()

	NewHandler(nil).Join(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
}

func TestJoin_서비스_에러를_올바른_응답으로_변환한다(t *testing.T) {
	t.Parallel()

	stub := &stubService{joinErr: apperr.NotMember()}
	req := httptest.NewRequest(http.MethodPost, "/voice/channels/7/join", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "7"))
	req = req.WithContext(withUserID(req.Context(), 99))
	rec := httptest.NewRecorder()

	NewHandler(stub).Join(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusForbidden)
	}
}

func TestJoin_성공하면_200과_응답을_반환한다(t *testing.T) {
	t.Parallel()

	stub := &stubService{
		joinResp: &JoinResponse{Token: "tok", LiveKitURL: "wss://lk", SessionID: "s1", RoomName: "voice-7-s1"},
	}
	req := httptest.NewRequest(http.MethodPost, "/voice/channels/7/join", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "7"))
	req = req.WithContext(withUserID(req.Context(), 99))
	rec := httptest.NewRecorder()

	NewHandler(stub).Join(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("Content-Type = %q, want application/json", ct)
	}
	var body JoinResponse
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if body.Token != "tok" {
		t.Fatalf("token = %q, want tok", body.Token)
	}
	if stub.gotChannelID != 7 || stub.gotUserID != 99 {
		t.Fatalf("service got channelID=%d userID=%d, want 7 99", stub.gotChannelID, stub.gotUserID)
	}
}

func TestLeave_InvalidChannelID_ReturnsBadRequest(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodPost, "/voice/channels/abc/leave", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "abc"))
	req = req.WithContext(withUserID(req.Context(), 1))
	rec := httptest.NewRecorder()

	NewHandler(nil).Leave(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusBadRequest)
	}
}

func TestLeave_UserID없으면_401을_반환한다(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodPost, "/voice/channels/1/leave", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "1"))
	rec := httptest.NewRecorder()

	NewHandler(nil).Leave(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
}

func TestLeave_성공하면_204와_빈_바디를_반환한다(t *testing.T) {
	t.Parallel()

	stub := &stubService{}
	req := httptest.NewRequest(http.MethodPost, "/voice/channels/5/leave", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "5"))
	req = req.WithContext(withUserID(req.Context(), 11))
	rec := httptest.NewRecorder()

	NewHandler(stub).Leave(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNoContent)
	}
	if rec.Body.Len() != 0 {
		t.Fatalf("body = %q, want empty", rec.Body.String())
	}
	if stub.gotChannelID != 5 || stub.gotUserID != 11 {
		t.Fatalf("service got channelID=%d userID=%d, want 5 11", stub.gotChannelID, stub.gotUserID)
	}
}

func TestParticipants_InvalidChannelID_ReturnsBadRequest(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodGet, "/voice/channels/xyz/participants", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "xyz"))
	req = req.WithContext(withUserID(req.Context(), 1))
	rec := httptest.NewRecorder()

	NewHandler(nil).Participants(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusBadRequest)
	}
}

func TestParticipants_UserID없으면_401을_반환한다(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodGet, "/voice/channels/3/participants", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "3"))
	rec := httptest.NewRecorder()

	NewHandler(nil).Participants(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
}

func TestParticipants_성공하면_200과_응답을_반환한다(t *testing.T) {
	t.Parallel()

	stub := &stubService{
		partResp: &ParticipantsResponse{
			ChannelID:    3,
			RoomName:     "voice-3-s1",
			Participants: []ParticipantResponse{{UserID: 10, JoinedAt: time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC).Format(time.RFC3339)}},
		},
	}
	req := httptest.NewRequest(http.MethodGet, "/voice/channels/3/participants", nil)
	req = req.WithContext(withRouteContext(req.Context(), "channel_id", "3"))
	req = req.WithContext(withUserID(req.Context(), 10))
	rec := httptest.NewRecorder()

	NewHandler(stub).Participants(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("Content-Type = %q, want application/json", ct)
	}
	var body ParticipantsResponse
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(body.Participants) != 1 {
		t.Fatalf("participants count = %d, want 1", len(body.Participants))
	}
	if stub.gotChannelID != 3 || stub.gotUserID != 10 {
		t.Fatalf("service got channelID=%d userID=%d, want 3 10", stub.gotChannelID, stub.gotUserID)
	}
}

func TestGetSession_UserID없으면_401을_반환한다(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodGet, "/voice/sessions/sess-1", nil)
	req = req.WithContext(withRouteContext(req.Context(), "session_id", "sess-1"))
	rec := httptest.NewRecorder()

	NewHandler(nil).GetSession(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
}

func TestGetSession_서비스_NotFound를_404로_반환한다(t *testing.T) {
	t.Parallel()

	stub := &stubService{sessErr: apperr.NotFound("세션을 찾을 수 없습니다.")}
	req := httptest.NewRequest(http.MethodGet, "/voice/sessions/sess-1", nil)
	req = req.WithContext(withRouteContext(req.Context(), "session_id", "sess-1"))
	req = req.WithContext(withUserID(req.Context(), 5))
	rec := httptest.NewRecorder()

	NewHandler(stub).GetSession(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
}

func TestGetSession_성공하면_200과_응답을_반환한다(t *testing.T) {
	t.Parallel()

	stub := &stubService{
		sessResp: &SessionResponse{SessionID: "sess-1", ChannelID: 3, TeamID: 1, Status: "active", StartedAt: time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC).Format(time.RFC3339)},
	}
	req := httptest.NewRequest(http.MethodGet, "/voice/sessions/sess-1", nil)
	req = req.WithContext(withRouteContext(req.Context(), "session_id", "sess-1"))
	req = req.WithContext(withUserID(req.Context(), 5))
	rec := httptest.NewRecorder()

	NewHandler(stub).GetSession(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("Content-Type = %q, want application/json", ct)
	}
	var body SessionResponse
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if body.SessionID != "sess-1" {
		t.Fatalf("session_id = %q, want sess-1", body.SessionID)
	}
	if stub.gotSessionID != "sess-1" || stub.gotUserID != 5 {
		t.Fatalf("service got sessionID=%q userID=%d, want sess-1 5", stub.gotSessionID, stub.gotUserID)
	}
}

func TestToAppError_SanitizesUnexpectedInternalError(t *testing.T) {
	t.Parallel()

	appErr := toAppError(errors.New("mongo timeout: replica set unavailable"))

	if appErr.HTTPStatus != http.StatusInternalServerError {
		t.Fatalf("HTTPStatus = %d, want %d", appErr.HTTPStatus, http.StatusInternalServerError)
	}
	if appErr.Message != "일시적인 서버 오류가 발생했습니다." {
		t.Fatalf("message = %q, want generic internal message", appErr.Message)
	}
}

func TestToAppError_PreservesKnownAppError(t *testing.T) {
	t.Parallel()

	appErr := toAppError(apperr.BadRequest("invalid channel_id"))

	if appErr.HTTPStatus != http.StatusBadRequest {
		t.Fatalf("HTTPStatus = %d, want %d", appErr.HTTPStatus, http.StatusBadRequest)
	}
	if appErr.Message != "invalid channel_id" {
		t.Fatalf("message = %q, want invalid channel_id", appErr.Message)
	}
}

func withRouteContext(ctx context.Context, key, value string) context.Context {
	routeCtx := chi.NewRouteContext()
	routeCtx.URLParams.Add(key, value)
	return context.WithValue(ctx, chi.RouteCtxKey, routeCtx)
}

func withUserID(ctx context.Context, userID int64) context.Context {
	return context.WithValue(ctx, middleware.UserIDKey, userID)
}
