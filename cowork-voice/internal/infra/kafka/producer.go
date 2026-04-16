package kafka

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

type Producer struct {
	writer *kafka.Writer
}

func NewProducer(brokers, topic string, messageTimeoutMs int) *Producer {
	addrs := strings.Split(brokers, ",")
	w := &kafka.Writer{
		Addr:         kafka.TCP(addrs...),
		Topic:        topic,
		Balancer:     &kafka.Hash{},
		WriteTimeout: time.Duration(messageTimeoutMs) * time.Millisecond,
		RequiredAcks: kafka.RequireOne,
		Async:        false,
	}
	return &Producer{writer: w}
}

func (p *Producer) Publish(ctx context.Context, sessionID string, v any) error {
	payload, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return p.writer.WriteMessages(ctx, kafka.Message{
		Key:   []byte(sessionID),
		Value: payload,
	})
}

func (p *Producer) Close() error {
	return p.writer.Close()
}
