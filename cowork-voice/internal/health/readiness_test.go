package health

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRequireReady_준비상태_전환_전후로_요청_통과여부가_바뀐다(t *testing.T) {
	t.Parallel()

	// Arrange
	readiness := NewReadiness()
	protected := RequireReady(readiness)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	newJoinRequest := func() *http.Request {
		return httptest.NewRequest(http.MethodPost, "/voice/channels/1/join", nil)
	}

	// Act & Assert: 준비되기 전에는 503으로 막는다.
	before := httptest.NewRecorder()
	protected.ServeHTTP(before, newJoinRequest())
	if before.Code != http.StatusServiceUnavailable {
		t.Fatalf("status before ready = %d, want %d", before.Code, http.StatusServiceUnavailable)
	}

	// Act & Assert: Set(true) 이후에는 요청을 통과시키고 Wait()도 준비 상태를 관측한다.
	readiness.Set(true)
	after := httptest.NewRecorder()
	protected.ServeHTTP(after, newJoinRequest())
	if after.Code != http.StatusNoContent {
		t.Fatalf("status after ready = %d, want %d", after.Code, http.StatusNoContent)
	}
	if !readiness.Wait(context.Background()) {
		t.Fatal("Wait() did not observe ready state")
	}
}
