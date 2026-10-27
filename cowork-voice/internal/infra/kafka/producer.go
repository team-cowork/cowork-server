package kafka

import (
	"context"
	"strings"
	"time"

	"github.com/cowork/cowork-voice/internal/config"
	"github.com/segmentio/kafka-go"
)

type Producer struct {
	transport *kafka.Transport
	writer    *kafka.Writer
}

func NewProducer(security config.KafkaSecurity, brokers, topic string, messageTimeoutMs int) *Producer {
	transport := security.Transport()
	addrs := strings.Split(brokers, ",")
	w := &kafka.Writer{
		Transport:    transport,
		Addr:         kafka.TCP(addrs...),
		Topic:        topic,
		Balancer:     &kafka.Hash{},
		WriteTimeout: time.Duration(messageTimeoutMs) * time.Millisecond,
		RequiredAcks: kafka.RequireOne,
		Async:        false,
	}
	return &Producer{writer: w, transport: transport}
}

// PublishRaw는 이미 직렬화된 페이로드를 그대로 전송한다(outbox relay 전용).
func (p *Producer) PublishRaw(ctx context.Context, key string, payload []byte) error {
	return p.writer.WriteMessages(ctx, kafka.Message{
		Key:   []byte(key),
		Value: payload,
	})
}

func (p *Producer) Close() error {
	defer p.transport.CloseIdleConnections()
	return p.writer.Close()
}
