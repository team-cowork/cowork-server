package service

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"strings"

	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/repository"
)

const signaturePrefix = "sha256="

// EventPublisher publishes a sync message to the user sync stream.
type EventPublisher interface {
	Publish(ctx context.Context, key string, value []byte) error
}

// DataGSM webhook envelope.
type WebhookEvent struct {
	ID        string          `json:"id"`
	Event     string          `json:"event"`
	Timestamp string          `json:"timestamp"`
	Data      json.RawMessage `json:"data"`
}

type studentEventData struct {
	StudentID int64  `json:"student_id"`
	Name      string `json:"name"`
	Email     string `json:"email"`
	Status    string `json:"status"`
}

// userSyncMessage is consumed by cowork-user's Kafka SyncHandler.
// event_type drives a targeted update there (partial role change, not a full upsert).
type userSyncMessage struct {
	EventType    string `json:"event_type"`
	EventID      string `json:"event_id"`
	Email        string `json:"email"`
	Name         string `json:"name"`
	StudentRole  string `json:"student_role,omitempty"`
	DataGSMRefID int64  `json:"datagsm_student_id"`
}

var supportedStudentEvents = map[string]struct{}{
	"student.graduated":      {},
	"student.withdrawn":      {},
	"student.status_changed": {},
}

type EventService struct {
	cfg           *config.AppConfig
	publisher     EventPublisher
	processedRepo *repository.ProcessedEventRepository
}

func NewEventService(
	cfg *config.AppConfig,
	publisher EventPublisher,
	processedRepo *repository.ProcessedEventRepository,
) *EventService {
	return &EventService{
		cfg:           cfg,
		publisher:     publisher,
		processedRepo: processedRepo,
	}
}

// SecretConfigured reports whether webhook verification is available.
func (s *EventService) SecretConfigured() bool {
	return s.cfg.DataGSMWebhookSecret != ""
}

// VerifySignature validates the X-DataGSM-Signature header against the raw body.
func (s *EventService) VerifySignature(body []byte, signatureHeader string) bool {
	if s.cfg.DataGSMWebhookSecret == "" {
		return false
	}
	provided := strings.TrimPrefix(signatureHeader, signaturePrefix)
	if provided == signatureHeader || provided == "" {
		return false
	}

	mac := hmac.New(sha256.New, []byte(s.cfg.DataGSMWebhookSecret))
	mac.Write(body)
	expected := hex.EncodeToString(mac.Sum(nil))

	return hmac.Equal([]byte(expected), []byte(provided))
}

// ProcessEvent parses the verified webhook body and forwards student lifecycle
// changes to the user sync stream. Idempotent on the event id.
func (s *EventService) ProcessEvent(ctx context.Context, body []byte) error {
	var envelope WebhookEvent
	if err := json.Unmarshal(body, &envelope); err != nil {
		return fmt.Errorf("failed to parse webhook envelope: %w", err)
	}
	if envelope.ID == "" || envelope.Event == "" {
		return fmt.Errorf("webhook envelope missing id or event")
	}

	if _, ok := supportedStudentEvents[envelope.Event]; !ok {
		log.Printf("ignoring unsupported webhook event: %s", envelope.Event)
		return nil
	}

	isNew, err := s.processedRepo.MarkProcessed(envelope.ID, envelope.Event)
	if err != nil {
		return fmt.Errorf("failed to record processed event: %w", err)
	}
	if !isNew {
		log.Printf("duplicate webhook event ignored: %s (%s)", envelope.ID, envelope.Event)
		return nil
	}

	var data studentEventData
	if err := json.Unmarshal(envelope.Data, &data); err != nil {
		return fmt.Errorf("failed to parse student event data: %w", err)
	}
	if data.Email == "" {
		return fmt.Errorf("student event %s missing email", envelope.ID)
	}

	msg := userSyncMessage{
		EventType:    envelope.Event,
		EventID:      envelope.ID,
		Email:        data.Email,
		Name:         data.Name,
		StudentRole:  resolveStudentRole(envelope.Event, data.Status),
		DataGSMRefID: data.StudentID,
	}

	payload, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("failed to marshal sync message: %w", err)
	}

	if err := s.publisher.Publish(ctx, data.Email, payload); err != nil {
		return fmt.Errorf("failed to publish sync message: %w", err)
	}

	return nil
}

func resolveStudentRole(event, status string) string {
	switch event {
	case "student.graduated":
		return "GRADUATE"
	case "student.withdrawn":
		return "WITHDRAWN"
	case "student.status_changed":
		return status
	default:
		return status
	}
}
