package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestExtractAuthUserAccordingToForwardedIdentity(t *testing.T) {
	t.Run("missing or malformed user id is rejected", func(t *testing.T) {
		for _, raw := range []string{"", "not-a-number"} {
			t.Run(raw, func(t *testing.T) {
				req := httptest.NewRequest(http.MethodGet, "/notifications/tokens", nil)
				req.Header.Set("X-User-Id", raw)
				recorder := httptest.NewRecorder()
				nextCalled := false
				handler := ExtractAuthUser(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
					nextCalled = true
				}))

				handler.ServeHTTP(recorder, req)

				if recorder.Code != http.StatusUnauthorized {
					t.Fatalf("status = %d, want %d", recorder.Code, http.StatusUnauthorized)
				}
				if nextCalled {
					t.Fatal("unauthenticated request reached the next handler")
				}
			})
		}
	})

	t.Run("valid user id is exposed to the protected handler", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/notifications/tokens", nil)
		req.Header.Set("X-User-Id", "42")
		recorder := httptest.NewRecorder()
		handler := ExtractAuthUser(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			accountID, ok := AccountIDFromContext(r.Context())
			if !ok || accountID != 42 {
				t.Fatalf("authenticated account = (%d, %v), want (42, true)", accountID, ok)
			}
			w.WriteHeader(http.StatusNoContent)
		}))

		handler.ServeHTTP(recorder, req)

		if recorder.Code != http.StatusNoContent {
			t.Fatalf("status = %d, want %d", recorder.Code, http.StatusNoContent)
		}
	})
}
