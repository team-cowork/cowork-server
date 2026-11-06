package kafka

import (
	"testing"
	"time"

	"github.com/cowork/authorization/internal/config"
)

func TestNewProducerUsesShortBatchTimeout(t *testing.T) {
	t.Parallel()

	producer := NewProducer(config.KafkaSecurity{}, "localhost:9092", "unused")
	t.Cleanup(func() { _ = producer.Close() })

	if got, want := producer.writer.BatchTimeout, 10*time.Millisecond; got != want {
		t.Fatalf("BatchTimeout = %v, want %v", got, want)
	}
}
