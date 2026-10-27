package fcm

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBuildMessageKeepsNotificationByDefault(t *testing.T) {
	input := Message{FID: "fid-1", Data: map[string]string{DataKeyAccountID: "7"}}

	msg := (&Sender{}).buildMessage(input, "제목", "본문")

	require.NotNil(t, msg.Notification)
	assert.Equal(t, "제목", msg.Notification.Title)
	assert.Equal(t, "본문", msg.Notification.Body)
	assert.Equal(t, map[string]string{DataKeyAccountID: "7", DataKeyTitle: "제목", DataKeyBody: "본문"}, msg.Data)
	assert.Nil(t, msg.APNS)
	assert.NotContains(t, input.Data, DataKeyTitle, "input data must not be mutated")
}

func TestBuildMessageDataOnlyOmitsNotification(t *testing.T) {
	msg := (&Sender{dataOnly: true}).buildMessage(
		Message{FID: "fid-1", Data: map[string]string{DataKeyAccountID: "7"}}, "제목", "본문")

	assert.Nil(t, msg.Notification)
	assert.Equal(t, "fid-1", msg.Fid)
	assert.Equal(t, map[string]string{DataKeyAccountID: "7", DataKeyTitle: "제목", DataKeyBody: "본문"}, msg.Data)
	require.NotNil(t, msg.Android)
	assert.Equal(t, "high", msg.Android.Priority)
	require.NotNil(t, msg.APNS)
	assert.Equal(t, "alert", msg.APNS.Headers["apns-push-type"])
	assert.Equal(t, "10", msg.APNS.Headers["apns-priority"])
	assert.True(t, msg.APNS.Payload.Aps.MutableContent)
	assert.False(t, msg.APNS.Payload.Aps.ContentAvailable)
	require.NotNil(t, msg.APNS.Payload.Aps.Alert)
	assert.Equal(t, "제목", msg.APNS.Payload.Aps.Alert.Title)
	assert.Equal(t, "본문", msg.APNS.Payload.Aps.Alert.Body)
}
