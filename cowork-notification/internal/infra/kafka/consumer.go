package kafka

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	segkafka "github.com/segmentio/kafka-go"
)

var errInvalidNotificationEvent = errors.New("invalid notification event")

type NotificationTriggerEvent struct {
	Type          string         `json:"type"`
	TargetUserIDs []int64        `json:"targetUserIds"`
	ForcedUserIDs []int64        `json:"forcedUserIds"`
	Data          map[string]any `json:"data"`
}

type NotificationService interface {
	Notify(ctx context.Context, targetUserIDs []int64, forcedUserIDs []int64, title, body string, channelID int64) ([]int64, error)
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

type projectionGate interface {
	Wait(context.Context) bool
}

type notificationReader interface {
	FetchMessage(context.Context) (segkafka.Message, error)
	CommitMessages(context.Context, ...segkafka.Message) error
	Close() error
}

type Consumer struct {
	reader         notificationReader
	svc            NotificationService
	teamNames      TeamNameResolver
	userNames      UserNameResolver
	sseBroadcaster SSEBroadcaster
	projectionGate projectionGate
}

func NewConsumer(
	brokers, topic, groupID string,
	svc NotificationService,
	teamNames TeamNameResolver,
	userNames UserNameResolver,
	sseBroadcaster SSEBroadcaster,
	gate projectionGate,
) *Consumer {
	return &Consumer{
		reader: segkafka.NewReader(segkafka.ReaderConfig{
			Brokers: splitBrokerList(brokers),
			Topic:   topic,
			GroupID: groupID,
		}),
		svc:            svc,
		teamNames:      teamNames,
		userNames:      userNames,
		sseBroadcaster: sseBroadcaster,
		projectionGate: gate,
	}
}

// NewConsumerForTest returns a Consumer with no Kafka reader — for unit tests only.
func NewConsumerForTest(svc NotificationService, teamNames TeamNameResolver, userNames UserNameResolver) *Consumer {
	return &Consumer{svc: svc, teamNames: teamNames, userNames: userNames}
}

// HandleForTest exposes handle for unit testing.
func (c *Consumer) HandleForTest(ctx context.Context, msg segkafka.Message) {
	_ = c.handle(ctx, msg)
}

func (c *Consumer) Start(ctx context.Context) {
	if c.reader == nil {
		panic("kafka: Consumer.Start called on test-only Consumer with nil reader")
	}
	for {
		if c.projectionGate != nil && !c.projectionGate.Wait(ctx) {
			return
		}
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			slog.Error("kafka read error", "err", err)
			continue
		}
		// The projection assignment may have changed while FetchMessage was
		// blocked. Never process the fetched trigger until the new barrier closes.
		if c.projectionGate != nil && !c.projectionGate.Wait(ctx) {
			return
		}
		if !c.handleWithRetry(ctx, msg) {
			return
		}
		for {
			if err := c.reader.CommitMessages(ctx, msg); err == nil {
				break
			} else {
				slog.Error("notification kafka commit failed; retrying", "err", err, "offset", msg.Offset)
			}
			if !waitForRetry(ctx) {
				return
			}
		}
	}
}

func (c *Consumer) handleWithRetry(ctx context.Context, msg segkafka.Message) bool {
	for {
		err := c.handle(ctx, msg)
		if err == nil {
			return true
		}
		if errors.Is(err, errInvalidNotificationEvent) {
			slog.Error("invalid notification event skipped", "err", err, "offset", msg.Offset)
			return true
		}
		slog.Error("notification processing failed; retrying", "err", err, "offset", msg.Offset)
		if !waitForRetry(ctx) {
			return false
		}
	}
}

func (c *Consumer) Close() error {
	if c.reader == nil {
		return nil
	}
	return c.reader.Close()
}

func (c *Consumer) handle(ctx context.Context, msg segkafka.Message) error {
	var event NotificationTriggerEvent
	decoder := json.NewDecoder(bytes.NewReader(msg.Value))
	decoder.UseNumber()
	if err := decoder.Decode(&event); err != nil {
		return fmt.Errorf("%w: %v", errInvalidNotificationEvent, err)
	}

	title, body, ok := c.buildMessage(ctx, event)
	if !ok {
		slog.Warn("알림 생성 실패로 스킵", "type", event.Type)
		return nil
	}

	channelID := extractInt64(event.Data, "channelId")
	enabledUserIDs, err := c.svc.Notify(
		ctx,
		event.TargetUserIDs,
		event.ForcedUserIDs,
		title,
		body,
		channelID,
	)
	if err != nil {
		return err
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
			c.sseBroadcaster.Broadcast(enabledUserIDs, ssePayload)
		}
	}
	return nil
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
	case "GITHUB_COMMENT_CREATED":
		return c.buildGithubComment(event)
	default:
		return "알림", "", true
	}
}

func (c *Consumer) buildGithubComment(event NotificationTriggerEvent) (title, body string, ok bool) {
	repo, _ := event.Data["repo"].(string)
	parentType, _ := event.Data["parentType"].(string)
	commentAuthor, _ := event.Data["commentAuthor"].(string)
	commentBody, _ := event.Data["body"].(string)
	number := extractInt64(event.Data, "number")

	parentLabel := "이슈"
	if parentType == "PULL_REQUEST" {
		parentLabel = "PR"
	}

	title = fmt.Sprintf("%s 댓글", parentLabel)
	body = fmt.Sprintf("%s님이 %s #%d에 댓글을 남겼습니다: %s", commentAuthor, repo, number, truncate(commentBody, 100))
	return title, body, true
}

func (c *Consumer) buildChatMessage(ctx context.Context, event NotificationTriggerEvent) (title, body string, ok bool) {
	teamID := extractInt64(event.Data, "teamId")
	authorID := extractInt64(event.Data, "authorId")
	content, _ := event.Data["content"].(string)
	occurredAt, _ := event.Data["occurredAt"].(string)

	senderName, err := c.userNames.GetDisplayName(ctx, authorID)
	if err != nil {
		slog.Warn("발신자 프로필 projection 조회 실패 — 기본 이름 사용", "authorId", authorID, "err", err)
		senderName = fmt.Sprintf("사용자 %d", authorID)
	}

	// DM 채널 메시지는 팀에 속하지 않아 teamId가 없다(0). 발신자 이름을 제목으로 사용한다.
	if teamID == 0 {
		title = senderName
		body = fmt.Sprintf("%s\n%s", truncate(content, 100), formatTime(occurredAt))
		return title, body, true
	}

	teamName, err := c.teamNames.GetName(ctx, teamID)
	if err != nil {
		slog.Warn("팀 projection 조회 실패 — 기본 이름 사용", "teamId", teamID, "err", err)
		teamName = fmt.Sprintf("팀 %d", teamID)
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
	case json.Number:
		value, err := n.Int64()
		if err == nil {
			return value
		}
	}
	return 0
}
