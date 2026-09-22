package service

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/domain"
)

const signaturePrefix = "sha256="

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

func (s *EventService) SecretConfigured() bool {
	return s.cfg.DataGSMWebhookSecret != ""
}

func (s *EventService) VerifySignature(body []byte, signatureHeader string) bool {
	if !s.SecretConfigured() {
		return false
	}
	provided, ok := strings.CutPrefix(signatureHeader, signaturePrefix)
	if !ok {
		return false
	}
	providedBytes, err := hex.DecodeString(provided)
	if err != nil || len(providedBytes) != sha256.Size {
		return false
	}
	mac := hmac.New(sha256.New, []byte(s.cfg.DataGSMWebhookSecret))
	mac.Write(body)
	return hmac.Equal(mac.Sum(nil), providedBytes)
}

// ProcessEvent acknowledges durable acceptance, not Kafka delivery or user mutation.
func (s *EventService) ProcessEvent(ctx context.Context, body []byte) (domain.WebhookResult, error) {
	envelope, occurredAt, err := parseWebhookEnvelope(body)
	if err != nil {
		return "", err
	}
	if envelope.Event != "student.updated" {
		return domain.WebhookIgnored, nil
	}
	items, err := parseStudentItems(envelope.Data)
	if err != nil {
		return "", err
	}
	canonical, err := json.Marshal(struct {
		Event     string        `json:"event"`
		Timestamp string        `json:"timestamp"`
		Items     []studentItem `json:"items"`
	}{envelope.Event, occurredAt.UTC().Format(time.RFC3339Nano), items})
	if err != nil {
		return "", err
	}
	batch := domain.WebhookBatch{
		EventID: envelope.ID, EventType: envelope.Event,
		OccurredAt: occurredAt, PayloadHash: sha256.Sum256(canonical),
	}
	envelope.Timestamp = occurredAt.UTC().Truncate(time.Microsecond).Format(time.RFC3339Nano)
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
