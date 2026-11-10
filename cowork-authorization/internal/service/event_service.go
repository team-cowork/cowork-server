package service

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
	"github.com/tmp-it-play/datagsm-sdk-go/event"
)

var ErrInvalidPayload = domain.ErrInvalidWebhookPayload

type WebhookInbox interface {
	SubmitBatch(context.Context, domain.WebhookBatch, string) (domain.WebhookResult, error)
}

type EventService struct {
	cfg   *config.AppConfig
	inbox WebhookInbox
}

func NewEventService(cfg *config.AppConfig, inbox WebhookInbox) *EventService {
	return &EventService{cfg: cfg, inbox: inbox}
}

// ProcessEvent acknowledges durable acceptance, not Kafka delivery or user mutation.
func (s *EventService) ProcessEvent(ctx context.Context, body []byte, signature string) (domain.WebhookResult, error) {
	envelope, err := parseProviderEvent(body, signature, s.cfg.DataGSMWebhookSecret)
	if err != nil {
		return "", err
	}
	if envelope.Type != event.StudentUpdated {
		return domain.WebhookIgnored, nil
	}
	items, err := studentItems(envelope)
	if err != nil {
		return "", err
	}
	occurredAt := envelope.Timestamp.UTC()
	canonical, err := json.Marshal(struct {
		Event     string        `json:"event"`
		Timestamp string        `json:"timestamp"`
		Items     []studentItem `json:"items"`
	}{string(envelope.Type), occurredAt.UTC().Format(time.RFC3339Nano), items})
	if err != nil {
		return "", err
	}
	batch := domain.WebhookBatch{
		EventID: envelope.ID, EventType: string(envelope.Type),
		OccurredAt: occurredAt, PayloadHash: sha256.Sum256(canonical),
	}
	for _, message := range studentMessages(envelope, items) {
		payload, err := json.Marshal(message)
		if err != nil {
			return "", err
		}
		batch.Messages = append(batch.Messages, domain.WebhookMessage{
			Index: message.EventIndex, Key: studentKey(message.DataGSMRefID), Payload: payload,
		})
	}
	return s.inbox.SubmitBatch(ctx, batch, s.cfg.KafkaTopicUserSync)
}
