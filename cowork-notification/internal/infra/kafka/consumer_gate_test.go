package kafka

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/cowork/cowork-notification/internal/health"
	segkafka "github.com/segmentio/kafka-go"
)

type blockingNotificationReader struct {
	fetched chan struct{}
}

func (r *blockingNotificationReader) FetchMessage(ctx context.Context) (segkafka.Message, error) {
	select {
	case <-r.fetched:
	default:
		close(r.fetched)
	}
	<-ctx.Done()
	return segkafka.Message{}, ctx.Err()
}

func (*blockingNotificationReader) CommitMessages(context.Context, ...segkafka.Message) error {
	return errors.New("not used")
}

func (*blockingNotificationReader) Close() error {
	return nil
}

func TestNotificationConsumer_doesNotFetchTriggerBeforeProjectionBarrier(t *testing.T) {
	t.Parallel()
	readiness := health.NewReadiness()
	reader := &blockingNotificationReader{fetched: make(chan struct{})}
	consumer := &Consumer{reader: reader, projectionGate: readiness}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		consumer.Start(ctx)
		close(done)
	}()

	select {
	case <-reader.fetched:
		t.Fatal("trigger consumer fetched before projection barrier")
	case <-time.After(50 * time.Millisecond):
	}

	readiness.Set(true)
	select {
	case <-reader.fetched:
	case <-time.After(time.Second):
		t.Fatal("trigger consumer did not resume after projection barrier")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("trigger consumer did not stop after context cancellation")
	}
}
