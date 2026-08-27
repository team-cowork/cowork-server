package health

import (
	"context"
	"net/http"
	"sync"
	"sync/atomic"
)

// Readiness is true only after every projection topic partition has reached
// the fixed high-watermark captured for the active Kafka assignment.
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
			http.Error(w, "projections not ready", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ready"))
	}
}
