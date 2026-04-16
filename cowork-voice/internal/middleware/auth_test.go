package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func Test인증_헤더가_없으면_401을_반환한다(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodGet, "/voice/channels/1/participants", nil)
	rec := httptest.NewRecorder()

	nextCalled := false
	handler := ExtractAuthUser(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		nextCalled = true
		w.WriteHeader(http.StatusNoContent)
	}))

	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
	if nextCalled {
		t.Fatal("next handler should not be called")
	}
}

func Test유효한_인증_헤더면_컨텍스트에_사용자_ID를_저장한다(t *testing.T) {
	t.Parallel()

	req := httptest.NewRequest(http.MethodGet, "/voice/channels/1/participants", nil)
	req.Header.Set("X-User-Id", "42")
	rec := httptest.NewRecorder()

	handler := ExtractAuthUser(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userID, ok := GetUserID(r.Context())
		if !ok {
			t.Fatal("user id not found in context")
		}
		if userID != 42 {
			t.Fatalf("userID = %d, want 42", userID)
		}
		w.WriteHeader(http.StatusNoContent)
	}))

	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNoContent)
	}
}
