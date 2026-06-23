package fcm

import (
	"context"
	"errors"
	"testing"

	"firebase.google.com/go/v4/messaging"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeMessagingClient struct {
	calls []*messaging.MulticastMessage
}

func (f *fakeMessagingClient) SendEachForMulticast(_ context.Context, msg *messaging.MulticastMessage) (*messaging.BatchResponse, error) {
	f.calls = append(f.calls, msg)

	responses := make([]*messaging.SendResponse, len(msg.Tokens))
	for i, token := range msg.Tokens {
		resp := &messaging.SendResponse{Success: true}
		switch token {
		case "expired-token":
			resp = &messaging.SendResponse{Success: false, Error: errUnregisteredToken}
		case "temporary-error-token":
			resp = &messaging.SendResponse{Success: false, Error: errors.New("temporary fcm error")}
		}
		responses[i] = resp
	}

	return &messaging.BatchResponse{Responses: responses}, nil
}

var errUnregisteredToken = errors.New("unregistered token")

func TestSender_Send_usesLatestMulticastAPIInBatches(t *testing.T) {
	client := &fakeMessagingClient{}
	sender := &Sender{
		client: client,
		isUnregistered: func(err error) bool {
			return errors.Is(err, errUnregisteredToken)
		},
	}
	tokens := make([]string, 501)
	for i := range tokens {
		tokens[i] = "token"
	}
	tokens[499] = "expired-token"
	tokens[500] = "temporary-error-token"

	invalid, err := sender.Send(context.Background(), tokens, "title", "body", map[string]string{"k": "v"})

	require.NoError(t, err)
	assert.Equal(t, []string{"expired-token"}, invalid)
	require.Len(t, client.calls, 2)
	assert.Len(t, client.calls[0].Tokens, 500)
	assert.Len(t, client.calls[1].Tokens, 1)
	assert.Equal(t, "title", client.calls[0].Notification.Title)
	assert.Equal(t, "body", client.calls[0].Notification.Body)
	assert.Equal(t, map[string]string{"k": "v"}, client.calls[0].Data)
}

func TestSender_Send_doesNotPanicWhenUnregisteredCheckerIsNil(t *testing.T) {
	sender := &Sender{client: &fakeMessagingClient{}}

	assert.NotPanics(t, func() {
		invalid, err := sender.Send(context.Background(), []string{"temporary-error-token"}, "title", "body", nil)

		require.NoError(t, err)
		assert.Empty(t, invalid)
	})
}
