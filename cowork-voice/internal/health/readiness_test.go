package health

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRequireReady_blocksBusinessRoutesButKeepsLivenessIndependent(t *testing.T) {
	t.Parallel()
	readiness := NewReadiness()
	protected := RequireReady(readiness)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))

	before := httptest.NewRecorder()
	protected.ServeHTTP(before, httptest.NewRequest(http.MethodPost, "/voice/channels/1/join", nil))
	if before.Code != http.StatusServiceUnavailable {
		t.Fatalf("status before ready = %d, want %d", before.Code, http.StatusServiceUnavailable)
	}
	readiness.Set(true)
	after := httptest.NewRecorder()
	protected.ServeHTTP(after, httptest.NewRequest(http.MethodPost, "/voice/channels/1/join", nil))
	if after.Code != http.StatusNoContent {
		t.Fatalf("status after ready = %d, want %d", after.Code, http.StatusNoContent)
	}
	if !readiness.Wait(context.Background()) {
		t.Fatal("Wait() did not observe ready state")
	}
}
