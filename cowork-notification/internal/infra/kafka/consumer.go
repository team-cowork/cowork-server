package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"time"

	segkafka "github.com/segmentio/kafka-go"
)

type NotificationTriggerEvent struct {
	Type          string         `json:"type"`
	TargetUserIDs []int64        `json:"targetUserIds"`
	ForcedUserIDs []int64        `json:"forcedUserIds"`
	Data          map[string]any `json:"data"`
}

type NotificationService interface {
	Notify(ctx context.Context, targetUserIDs []int64, forcedUserIDs []int64, title, body string, channelID int64) error
}

type TeamNameResolver interface {
	GetName(ctx context.Context, teamID int64) (string, error)
}

type UserNameResolver interface {
	GetDisplayName(ctx context.Context, userID int64) (string, error)
}

// SSEBroadcaster는 SSE Hub의 Broadcast 메서드 인터페이스입니다.
type SSEBroadcaster interface {
	Broadcast(userIDs []int64, payload []byte)
}

type Consumer struct {
	reader     *segkafka.Reader
	svc        NotificationService
	teamClient TeamNameResolver
	userClient UserNameResolver
	sseBroadcaster SSEBroadcaster
}

func NewConsumer(brokers, topic, groupID string, svc NotificationService, teamClient TeamNameResolver, userClient UserNameResolver, sseBroadcaster SSEBroadcaster) *Consumer {
	return &Consumer{
		reader: segkafka.NewReader(segkafka.ReaderConfig{
			Brokers: strings.Split(brokers, ","),
			Topic:   topic,
			GroupID: groupID,
		}),
		svc:            svc,
		teamClient:     teamClient,
		userClient:     userClient,
		sseBroadcaster: sseBroadcaster,
	}
}

// NewConsumerForTest returns a Consumer with no Kafka reader — for unit tests only.
func NewConsumerForTest(svc NotificationService, teamClient TeamNameResolver, userClient UserNameResolver) *Consumer {
	return &Consumer{svc: svc, teamClient: teamClient, userClient: userClient}
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

	title, body, ok := c.buildMessage(ctx, event)
	if !ok {
		slog.Warn("알림 생성 실패로 스킵", "type", event.Type)
		return
	}

	channelID := extractInt64(event.Data, "channelId")
	if err := c.svc.Notify(ctx, event.TargetUserIDs, event.ForcedUserIDs, title, body, channelID); err != nil {
		slog.Error("notification failed", "err", err, "type", event.Type)
	}

	// 데스크톱 앱(SSE)으로 알림 이벤트 브로드캐스트
	if c.sseBroadcaster != nil {
		ssePayload, err := json.Marshal(map[string]any{
			"type":      event.Type,
			"title":     title,
			"body":      body,
			"channelId": channelID,
			"teamId":    extractInt64(event.Data, "teamId"),
		})
		if err != nil {
			slog.Error("SSE 페이로드 직렬화 실패", "err", err, "type", event.Type)
		} else {
			c.sseBroadcaster.Broadcast(mergeUserIDs(event.TargetUserIDs, event.ForcedUserIDs), ssePayload)
		}
	}
}

func (c *Consumer) buildMessage(ctx context.Context, event NotificationTriggerEvent) (title, body string, ok bool) {
	switch event.Type {
	case "CHAT_MESSAGE":
		return c.buildChatMessage(ctx, event)
	case "MEMBER_INVITED":
		return "팀 초대", "팀에 초대되었습니다.", true
	case "MEMBER_REMOVED":
		return "팀 멤버 제거", "팀에서 제거되었습니다.", true
	case "PROJECT_TASK_ASSIGNED":
		return "태스크 할당", "새 태스크가 할당되었습니다.", true
	default:
		return "알림", "", true
	}
}

func (c *Consumer) buildChatMessage(ctx context.Context, event NotificationTriggerEvent) (title, body string, ok bool) {
	teamID := extractInt64(event.Data, "teamId")
	authorID := extractInt64(event.Data, "authorId")
	content, _ := event.Data["content"].(string)
	occurredAt, _ := event.Data["occurredAt"].(string)

	teamName, err := c.teamClient.GetName(ctx, teamID)
	if err != nil {
		slog.Error("팀 이름 조회 실패 — 알림 스킵", "teamId", teamID, "err", err)
		return "", "", false
	}

	senderName, err := c.userClient.GetDisplayName(ctx, authorID)
	if err != nil {
		slog.Error("발신자 이름 조회 실패 — 알림 스킵", "authorId", authorID, "err", err)
		return "", "", false
	}

	title = teamName
	body = fmt.Sprintf("%s: %s\n%s", senderName, truncate(content, 100), formatTime(occurredAt))
	return title, body, true
}

func truncate(s string, max int) string {
	runes := []rune(s)
	if len(runes) <= max {
		return s
	}
	return string(runes[:max]) + "..."
}

func formatTime(iso string) string {
	t, err := time.Parse(time.RFC3339Nano, iso)
	if err != nil {
		return iso
	}
	kst := t.In(time.FixedZone("KST", 9*60*60))
	return kst.Format("2006-01-02 15:04")
}

// mergeUserIDs는 두 슬라이스를 합치되 중복을 제거합니다.
func mergeUserIDs(a, b []int64) []int64 {
	seen := make(map[int64]struct{}, len(a)+len(b))
	result := make([]int64, 0, len(a)+len(b))
	for _, id := range append(a, b...) {
		if _, ok := seen[id]; !ok {
			seen[id] = struct{}{}
			result = append(result, id)
		}
	}
	return result
}

func extractInt64(data map[string]any, key string) int64 {
	if data == nil {
		return 0
	}
	v, ok := data[key]
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
