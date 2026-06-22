// Package relay drains the Mongo outbox and delivers messages to Kafka.
// transactional outbox 패턴의 전송 단계로, Kafka 장애 시에도 메시지를 잃지 않고 재시도한다.
package relay

import (
	"context"
	"log/slog"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"

	mongoinfra "github.com/cowork/cowork-voice/internal/infra/mongo"
)

type Outbox interface {
	FetchUnsent(ctx context.Context, limit int) ([]mongoinfra.OutboxMessage, error)
	MarkSent(ctx context.Context, id bson.ObjectID, sentAt time.Time) error
	IncrementAttempts(ctx context.Context, id bson.ObjectID) error
}

type Publisher interface {
	PublishRaw(ctx context.Context, key string, payload []byte) error
}

type Relay struct {
	outbox    Outbox
	publisher Publisher
	interval  time.Duration
	batchSize int
	stopCh    chan struct{}
	doneCh    chan struct{}
}

func New(outbox Outbox, publisher Publisher, interval time.Duration, batchSize int) *Relay {
	return &Relay{
		outbox:    outbox,
		publisher: publisher,
		interval:  interval,
		batchSize: batchSize,
		stopCh:    make(chan struct{}),
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
			case <-r.stopCh:
				return
			case <-ticker.C:
				r.drain()
			}
		}
	}()
}

func (r *Relay) Stop() {
	close(r.stopCh)
	<-r.doneCh
}

func (r *Relay) drain() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	msgs, err := r.outbox.FetchUnsent(ctx, r.batchSize)
	if err != nil {
		slog.Error("outbox relay: fetch failed", "err", err)
		return
	}

	for _, m := range msgs {
		if err := r.publisher.PublishRaw(ctx, m.Key, m.Payload); err != nil {
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
