// Package relay drains the Mongo outbox and delivers messages to Kafka.
// transactional outbox 패턴의 전송 단계로, Kafka 장애 시에도 메시지를 잃지 않고 재시도한다.
package relay

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"

	mongoinfra "github.com/cowork/cowork-voice/internal/infra/mongo"
)

// maxPublishAttempts를 초과해 실패한 메시지는 격리되어 큐를 막지 않는다(head-of-line blocking 방지).
const maxPublishAttempts = 50

type Outbox interface {
	FetchUnsent(ctx context.Context, limit int) ([]mongoinfra.OutboxMessage, error)
	MarkSent(ctx context.Context, id bson.ObjectID, sentAt time.Time) error
	IncrementAttempts(ctx context.Context, id bson.ObjectID) error
	MarkFailed(ctx context.Context, id bson.ObjectID, failedAt time.Time) error
}

type Publisher interface {
	PublishRaw(ctx context.Context, key string, payload []byte) error
}

type Relay struct {
	outbox    Outbox
	publisher Publisher
	interval  time.Duration
	batchSize int
	ctx       context.Context
	cancel    context.CancelFunc
	stopOnce  sync.Once
	doneCh    chan struct{}
}

func New(outbox Outbox, publisher Publisher, interval time.Duration, batchSize int) *Relay {
	ctx, cancel := context.WithCancel(context.Background())
	return &Relay{
		outbox:    outbox,
		publisher: publisher,
		interval:  interval,
		batchSize: batchSize,
		ctx:       ctx,
		cancel:    cancel,
		doneCh:    make(chan struct{}),
	}
}

func (r *Relay) Start() {
	go func() {
		defer close(r.doneCh)
		ticker := time.NewTicker(r.interval)
		defer ticker.Stop()
		for {
			select {
			case <-r.ctx.Done():
				return
			case <-ticker.C:
				r.drain()
			}
		}
	}()
}

func (r *Relay) Stop() {
	// sync.Once로 중복 호출 시 panic을 막고, ctx 취소로 진행 중인 I/O까지 즉시 중단한다.
	r.stopOnce.Do(r.cancel)
	<-r.doneCh
}

func (r *Relay) drain() {
	// r.ctx 파생 컨텍스트라 Stop() 시 진행 중인 Fetch/Publish가 즉시 취소되어 셧다운이 지연되지 않는다.
	ctx, cancel := context.WithTimeout(r.ctx, 30*time.Second)
	defer cancel()

	msgs, err := r.outbox.FetchUnsent(ctx, r.batchSize)
	if err != nil {
		slog.Error("outbox relay: fetch failed", "err", err)
		return
	}

	for _, m := range msgs {
		if err := r.publisher.PublishRaw(ctx, m.Key, m.Payload); err != nil {
			if m.Attempts+1 >= maxPublishAttempts {
				// 재시도 한도 초과: 격리해 큐를 막지 않는다. 격리된 메시지는 보존되어 사후 조회 가능.
				slog.Error("outbox relay: max attempts exceeded, quarantining message", "err", err, "key", m.Key, "attempts", m.Attempts+1, "id", m.ID.Hex())
				if ferr := r.outbox.MarkFailed(ctx, m.ID, time.Now().UTC()); ferr != nil {
					// 격리 실패 시 hot loop를 피하려 멈추고 다음 tick에 재시도한다.
					slog.Error("outbox relay: mark failed failed", "err", ferr, "key", m.Key)
					return
				}
				continue
			}
			slog.Warn("outbox relay: publish failed, will retry", "err", err, "key", m.Key, "attempts", m.Attempts)
			if ierr := r.outbox.IncrementAttempts(ctx, m.ID); ierr != nil {
				slog.Error("outbox relay: increment attempts failed", "err", ierr, "key", m.Key)
			}
			// 같은 key의 순서를 보존하기 위해 이후 메시지는 다음 tick에서 재시도한다.
			return
		}
		if err := r.outbox.MarkSent(ctx, m.ID, time.Now().UTC()); err != nil {
			// sent_at 기록 실패: 다음 tick에 재전송됨(중복 가능 → consumer 멱등성 전제).
			slog.Error("outbox relay: mark sent failed", "err", err, "key", m.Key)
			return
		}
	}
}
