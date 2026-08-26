package kafka

import (
	"context"
	"fmt"
	"net"
	"sort"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

type Producer struct {
	writer       *kafka.Writer
	addr         net.Addr
	defaultTopic string
}

type explicitPartition int

type partitionAwareBalancer struct {
	fallback kafka.Balancer
}

func (b *partitionAwareBalancer) Balance(message kafka.Message, partitions ...int) int {
	if requested, ok := message.WriterData.(explicitPartition); ok {
		// Returning the explicit id even after a topology change makes Kafka
		// reject the stale marker instead of silently hashing it elsewhere.
		return int(requested)
	}
	return b.fallback.Balance(message, partitions...)
}

func NewProducer(bootstrapServers, topic string) *Producer {
	addr := kafka.TCP(strings.Split(bootstrapServers, ",")...)
	return &Producer{
		writer: &kafka.Writer{
			Addr:         addr,
			Balancer:     &partitionAwareBalancer{fallback: &kafka.Hash{}},
			RequiredAcks: kafka.RequireAll,
			WriteTimeout: 5 * time.Second,
		},
		addr:         addr,
		defaultTopic: topic,
	}
}

func (p *Producer) Publish(ctx context.Context, key string, value []byte) error {
	return p.PublishTo(ctx, p.defaultTopic, key, value)
}

func (p *Producer) PublishTo(ctx context.Context, topic, key string, value []byte) error {
	return p.writer.WriteMessages(ctx, kafka.Message{
		Topic: topic,
		Key:   []byte(key),
		Value: value,
	})
}

func (p *Producer) PublishToPartition(
	ctx context.Context,
	topic string,
	partition int,
	key string,
	value []byte,
) error {
	return p.writer.WriteMessages(ctx, kafka.Message{
		Topic:      topic,
		Key:        []byte(key),
		Value:      value,
		WriterData: explicitPartition(partition),
	})
}

func (p *Producer) Partitions(ctx context.Context, topic string) ([]int, error) {
	response, err := (&kafka.Client{Addr: p.addr}).Metadata(ctx, &kafka.MetadataRequest{
		Topics: []string{topic},
	})
	if err != nil {
		return nil, err
	}
	for _, metadata := range response.Topics {
		if metadata.Name != topic {
			continue
		}
		if metadata.Error != nil {
			return nil, metadata.Error
		}
		partitions := make([]int, 0, len(metadata.Partitions))
		for _, partition := range metadata.Partitions {
			partitions = append(partitions, partition.ID)
		}
		if len(partitions) == 0 {
			return nil, fmt.Errorf("topic %q has no partitions", topic)
		}
		sort.Ints(partitions)
		return partitions, nil
	}
	return nil, fmt.Errorf("topic metadata not found: %s", topic)
}

func (p *Producer) Close() error {
	return p.writer.Close()
}
