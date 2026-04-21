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
	calledUserIDs []int64
	calledTitle   string
	calledBody    string
	calledChannel int64
	err           error
}

func (m *mockNotificationService) Notify(_ context.Context, ids []int64, title, body string, channelID int64) error {
	m.calledUserIDs = ids
	m.calledTitle = title
	m.calledBody = body
	m.calledChannel = channelID
	return m.err
}

func TestConsumer_handle_chatMessage(t *testing.T) {
	svc := &mockNotificationService{}
	c := kafkainfra.NewConsumerForTest(svc)

	event := kafkainfra.NotificationTriggerEvent{
		Type:          "CHAT_MESSAGE",
		TargetUserIDs: []int64{1, 2},
		Data:          map[string]any{"channelId": float64(42)},
	}
	raw, err := json.Marshal(event)
	require.NoError(t, err)

	c.HandleForTest(context.Background(), segkafka.Message{Value: raw})

	assert.Equal(t, []int64{1, 2}, svc.calledUserIDs)
	assert.Equal(t, "새 메시지", svc.calledTitle)
	assert.Equal(t, int64(42), svc.calledChannel)
}

func TestConsumer_handle_memberInvited(t *testing.T) {
	svc := &mockNotificationService{}
	c := kafkainfra.NewConsumerForTest(svc)

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
	c := kafkainfra.NewConsumerForTest(svc)

	c.HandleForTest(context.Background(), segkafka.Message{Value: []byte("not-json")})

	assert.Nil(t, svc.calledUserIDs)
}
