package health

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"sync/atomic"
)

var ErrReadinessEpochChanged = errors.New("projection readiness epoch changed")

// Readiness is true only after every projection topic partition has reached
// the fixed high-watermark captured for the active Kafka assignment.
type Readiness struct {
	ready   atomic.Bool
	mu      sync.Mutex
	changed chan struct{}
	epoch   uint64
	leases  map[*Lease]struct{}
}

func NewReadiness() *Readiness {
	return &Readiness{
		changed: make(chan struct{}),
		leases:  make(map[*Lease]struct{}),
	}
}

// Lease fences side effects to the readiness epoch in which work was admitted.
// Its context is canceled as soon as that epoch closes.
type Lease struct {
	readiness *Readiness
	epoch     uint64
	ctx       context.Context
	cancel    context.CancelCauseFunc
	closeOnce sync.Once
}

func (l *Lease) Context() context.Context {
	return l.ctx
}

func (l *Lease) Current() bool {
	if l == nil || l.ctx.Err() != nil {
		return false
	}
	l.readiness.mu.Lock()
	defer l.readiness.mu.Unlock()
	return l.readiness.ready.Load() && l.readiness.epoch == l.epoch
}

func (l *Lease) Close() {
	if l == nil {
		return
	}
	l.closeOnce.Do(func() {
		l.readiness.mu.Lock()
		delete(l.readiness.leases, l)
		l.readiness.mu.Unlock()
		l.cancel(context.Canceled)
	})
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
	for lease := range r.leases {
		lease.cancel(ErrReadinessEpochChanged)
		delete(r.leases, lease)
	}
	r.epoch++
	r.ready.Store(ready)
	close(r.changed)
	r.changed = make(chan struct{})
}

func (r *Readiness) WaitLease(ctx context.Context) (*Lease, bool) {
	for {
		if !r.Wait(ctx) {
			return nil, false
		}

		r.mu.Lock()
		if !r.ready.Load() {
			r.mu.Unlock()
			continue
		}
		epoch := r.epoch
		leaseCtx, cancel := context.WithCancelCause(ctx)
		lease := &Lease{
			readiness: r,
			epoch:     epoch,
			ctx:       leaseCtx,
			cancel:    cancel,
		}
		r.leases[lease] = struct{}{}
		r.mu.Unlock()
		if lease.Current() {
			return lease, true
		}
		lease.Close()
	}
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
