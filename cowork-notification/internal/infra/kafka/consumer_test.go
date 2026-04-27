package kafka_test

import (
	"context"
	"encoding/json"
	"testing"

	kafkainfra "github.com/cowork/cowork-notification/internal/infra/kafka"
	segkafka "github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockNotificationService struct {
	calledUserIDs  []int64
	calledForced   []int64
	calledTitle    string
	calledBody     string
	calledChannel  int64
	err            error
}

func (m *mockNotificationService) Notify(_ context.Context, ids []int64, forced []int64, title, body string, channelID int64) error {
	m.calledUserIDs = ids
	m.calledForced = forced
	m.calledTitle = title
	m.calledBody = body
	m.calledChannel = channelID
	return m.err
}

type mockTeamClient struct {
	name string
	err  error
}

func (m *mockTeamClient) GetName(_ context.Context, _ int64) (string, error) {
	return m.name, m.err
}

type mockUserClient struct {
	displayName string
	err         error
}

func (m *mockUserClient) GetDisplayName(_ context.Context, _ int64) (string, error) {
	return m.displayName, m.err
}

func TestConsumer_handle_chatMessage(t *testing.T) {
	svc := &mockNotificationService{}
	teamC := &mockTeamClient{name: "코워크팀"}
	userC := &mockUserClient{displayName: "홍길동"}
	c := kafkainfra.NewConsumerForTest(svc, teamC, userC)

	event := kafkainfra.NotificationTriggerEvent{
		Type:          "CHAT_MESSAGE",
		TargetUserIDs: []int64{1, 2},
		ForcedUserIDs: []int64{2},
		Data: map[string]any{
			"channelId":  float64(42),
			"teamId":     float64(7),
			"authorId":   float64(100),
			"content":    "안녕하세요",
			"occurredAt": "2026-04-23T14:32:00Z",
		},
	}
	raw, err := json.Marshal(event)
	require.NoError(t, err)

	c.HandleForTest(context.Background(), segkafka.Message{Value: raw})

	assert.Equal(t, []int64{1, 2}, svc.calledUserIDs)
	assert.Equal(t, []int64{2}, svc.calledForced)
	assert.Equal(t, "코워크팀", svc.calledTitle)
	assert.Contains(t, svc.calledBody, "홍길동: 안녕하세요")
	assert.Contains(t, svc.calledBody, "2026-04-23 23:32") // KST UTC+9
	assert.Equal(t, int64(42), svc.calledChannel)
}

func TestConsumer_handle_chatMessage_teamFailSkips(t *testing.T) {
	svc := &mockNotificationService{}
	teamC := &mockTeamClient{err: assert.AnError}
	userC := &mockUserClient{displayName: "홍길동"}
	c := kafkainfra.NewConsumerForTest(svc, teamC, userC)

	event := kafkainfra.NotificationTriggerEvent{
		Type:          "CHAT_MESSAGE",
		TargetUserIDs: []int64{1},
		Data: map[string]any{
			"channelId": float64(42), "teamId": float64(7),
			"authorId": float64(100), "content": "hi", "occurredAt": "2026-04-23T14:32:00Z",
		},
	}
	raw, _ := json.Marshal(event)
	c.HandleForTest(context.Background(), segkafka.Message{Value: raw})

	assert.Nil(t, svc.calledUserIDs)
}

func TestConsumer_handle_chatMessage_userFailSkips(t *testing.T) {
	svc := &mockNotificationService{}
	teamC := &mockTeamClient{name: "코워크팀"}
	userC := &mockUserClient{err: assert.AnError}
	c := kafkainfra.NewConsumerForTest(svc, teamC, userC)

	event := kafkainfra.NotificationTriggerEvent{
		Type:          "CHAT_MESSAGE",
		TargetUserIDs: []int64{1},
		Data: map[string]any{
			"channelId": float64(42), "teamId": float64(7),
			"authorId": float64(100), "content": "hi", "occurredAt": "2026-04-23T14:32:00Z",
		},
	}
	raw, _ := json.Marshal(event)
	c.HandleForTest(context.Background(), segkafka.Message{Value: raw})

	assert.Nil(t, svc.calledUserIDs)
}

func TestConsumer_handle_memberInvited(t *testing.T) {
	svc := &mockNotificationService{}
	c := kafkainfra.NewConsumerForTest(svc, &mockTeamClient{}, &mockUserClient{})

	event := kafkainfra.NotificationTriggerEvent{
		Type:          "MEMBER_INVITED",
		TargetUserIDs: []int64{5},
	}
	raw, err := json.Marshal(event)
	require.NoError(t, err)
	c.HandleForTest(context.Background(), segkafka.Message{Value: raw})

	assert.Equal(t, "팀 초대", svc.calledTitle)
	assert.Equal(t, int64(0), svc.calledChannel)
}

func TestConsumer_handle_invalidJSON(t *testing.T) {
	svc := &mockNotificationService{}
	c := kafkainfra.NewConsumerForTest(svc, &mockTeamClient{}, &mockUserClient{})

	c.HandleForTest(context.Background(), segkafka.Message{Value: []byte("not-json")})

	assert.Nil(t, svc.calledUserIDs)
}
