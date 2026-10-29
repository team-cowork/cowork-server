package redisinfra

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"github.com/cowork/authorization/internal/domain"
	redis "github.com/redis/go-redis/v9"
)

type fakeWebhookProcessor struct {
	valid  bool
	result domain.WebhookResult
	err    error
	calls  *[]string
}

func (p fakeWebhookProcessor) SecretConfigured() bool { return true }
func (p fakeWebhookProcessor) VerifySignature([]byte, string) bool {
	*p.calls = append(*p.calls, "verify")
	return p.valid
}
func (p fakeWebhookProcessor) ProcessEvent(context.Context, []byte) (domain.WebhookResult, error) {
	*p.calls = append(*p.calls, "persist")
	return p.result, p.err
}

func TestQueuedWebhookAcknowledgesOnlyAfterDurableProcessing(t *testing.T) {
	message := redis.XMessage{ID: "1-0", Values: map[string]interface{}{
		"body": `{"id":"event-1"}`, "signature": "sha256=valid",
	}}
	for _, test := range []struct {
		name    string
		valid   bool
		persist error
		ackErr  error
		want    []string
	}{
		{name: "accepted", valid: true, want: []string{"verify", "persist", "ack"}},
		{name: "invalid signature", valid: false, want: []string{"verify"}},
		{name: "database failure", valid: true, persist: errors.New("database unavailable"), want: []string{"verify", "persist"}},
		{name: "ack failure leaves entry pending", valid: true, ackErr: errors.New("redis unavailable"), want: []string{"verify", "persist", "ack"}},
	} {
		t.Run(test.name, func(t *testing.T) {
			calls := []string{}
			processor := fakeWebhookProcessor{valid: test.valid, result: domain.WebhookAccepted, err: test.persist, calls: &calls}
			_, err := processQueuedWebhook(context.Background(), processor, message, func(context.Context, string) error {
				calls = append(calls, "ack")
				return test.ackErr
			})
			if !reflect.DeepEqual(calls, test.want) {
				t.Fatalf("calls = %v, want %v", calls, test.want)
			}
			wantError := !test.valid || test.persist != nil || test.ackErr != nil
			if (err != nil) != wantError {
				t.Fatalf("unexpected error = %v", err)
			}
		})
	}
}
