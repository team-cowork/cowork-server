package session

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/go-chi/chi/v5"

	"github.com/cowork/cowork-voice/internal/apperr"
	"github.com/cowork/cowork-voice/internal/middleware"
)

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
	if body["status"] != "BAD_REQUEST" {
		t.Fatalf("status body = %v, want BAD_REQUEST", body["status"])
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
