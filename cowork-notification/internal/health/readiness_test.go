package health

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestReadyHandler_reportsUnavailableUntilProjectionBarrierCloses(t *testing.T) {
	t.Parallel()
	readiness := NewReadiness()
	handler := ReadyHandler(readiness)

	before := httptest.NewRecorder()
	handler.ServeHTTP(before, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
	if before.Code != http.StatusServiceUnavailable {
		t.Fatalf("status before ready = %d, want %d", before.Code, http.StatusServiceUnavailable)
	}

	readiness.Set(true)
	after := httptest.NewRecorder()
	handler.ServeHTTP(after, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
	if after.Code != http.StatusOK {
		t.Fatalf("status after ready = %d, want %d", after.Code, http.StatusOK)
	}
}
