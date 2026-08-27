package health

import (
	"context"
	"net/http"
	"sync"
	"sync/atomic"
)

// Readiness tracks whether the Kafka-backed authorization projection has
// reached the fixed startup high-watermark. Liveness is deliberately separate.
type Readiness struct {
	ready   atomic.Bool
	mu      sync.Mutex
	changed chan struct{}
}

func NewReadiness() *Readiness {
	return &Readiness{changed: make(chan struct{})}
}

func (r *Readiness) IsReady() bool {
	return r.ready.Load()
}

func (r *Readiness) Set(ready bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.ready.Load() == ready {
		return
	}
	r.ready.Store(ready)
	close(r.changed)
	r.changed = make(chan struct{})
}

func (r *Readiness) Wait(ctx context.Context) bool {
	for {
		r.mu.Lock()
		if r.ready.Load() {
			r.mu.Unlock()
			return true
		}
		changed := r.changed
		r.mu.Unlock()

		select {
		case <-ctx.Done():
			return false
		case <-changed:
		}
	}
}

func ReadyHandler(readiness *Readiness) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		if !readiness.IsReady() {
			http.Error(w, "projection not ready", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ready"))
	}
}

func RequireReady(readiness *Readiness) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !readiness.IsReady() {
				http.Error(w, "projection not ready", http.StatusServiceUnavailable)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
