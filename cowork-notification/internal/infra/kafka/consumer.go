package kafka

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"

	segkafka "github.com/segmentio/kafka-go"
)

type NotificationTriggerEvent struct {
	Type          string                 `json:"type"`
	TargetUserIDs []int64                `json:"targetUserIds"`
	Data          map[string]interface{} `json:"data"`
}

type NotificationService interface {
	Notify(ctx context.Context, targetUserIDs []int64, title, body string, channelID int64) error
}

type Consumer struct {
	reader *segkafka.Reader
	svc    NotificationService
}

func NewConsumer(brokers, topic, groupID string, svc NotificationService) *Consumer {
	return &Consumer{
		reader: segkafka.NewReader(segkafka.ReaderConfig{
			Brokers: strings.Split(brokers, ","),
			Topic:   topic,
			GroupID: groupID,
		}),
		svc: svc,
	}
}

// NewConsumerForTest returns a Consumer with no Kafka reader — for unit tests only.
func NewConsumerForTest(svc NotificationService) *Consumer {
	return &Consumer{svc: svc}
}

// HandleForTest exposes handle for unit testing.
func (c *Consumer) HandleForTest(ctx context.Context, msg segkafka.Message) {
	c.handle(ctx, msg)
}

func (c *Consumer) Start(ctx context.Context) {
	if c.reader == nil {
		panic("kafka: Consumer.Start called on test-only Consumer with nil reader")
	}
	for {
		msg, err := c.reader.ReadMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			slog.Error("kafka read error", "err", err)
			continue
		}
		c.handle(ctx, msg)
	}
}

func (c *Consumer) Close() error {
	if c.reader == nil {
		return nil
	}
	return c.reader.Close()
}

func (c *Consumer) handle(ctx context.Context, msg segkafka.Message) {
	var event NotificationTriggerEvent
	if err := json.Unmarshal(msg.Value, &event); err != nil {
		slog.Error("failed to unmarshal notification event", "err", err, "offset", msg.Offset)
		return
	}
	title, body := buildMessage(event.Type)
	channelID := extractChannelID(event.Data)
	if err := c.svc.Notify(ctx, event.TargetUserIDs, title, body, channelID); err != nil {
		slog.Error("notification failed", "err", err, "type", event.Type)
	}
}

func buildMessage(eventType string) (title, body string) {
	switch eventType {
	case "CHAT_MESSAGE":
		return "새 메시지", "새 메시지가 도착했습니다."
	case "MEMBER_INVITED":
		return "팀 초대", "팀에 초대되었습니다."
	case "MEMBER_REMOVED":
		return "팀 멤버 제거", "팀에서 제거되었습니다."
	case "PROJECT_TASK_ASSIGNED":
		return "태스크 할당", "새 태스크가 할당되었습니다."
	default:
		return "알림", ""
	}
}

func extractChannelID(data map[string]interface{}) int64 {
	if data == nil {
		return 0
	}
	v, ok := data["channelId"]
	if !ok {
		return 0
	}
	switch n := v.(type) {
	case float64:
		return int64(n)
	case int64:
		return n
	}
	return 0
}
