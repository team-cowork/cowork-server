package service

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strconv"
	"strings"
	"time"

	"github.com/cowork/authorization/internal/config"
)

const signaturePrefix = "sha256="

// ErrInvalidPayload marks client/payload errors so the handler can respond 4xx
// instead of triggering DataGSM retries with a 5xx.
var ErrInvalidPayload = errors.New("invalid webhook payload")

// EventPublisher publishes a sync message to the user sync stream.
type EventPublisher interface {
	Publish(ctx context.Context, key string, value []byte) error
}

// ProcessedEventStore records handled event ids for idempotency.
type ProcessedEventStore interface {
	Exists(eventID string) (bool, error)
	MarkProcessed(eventID, eventType string) (bool, error)
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
	Sex       string `json:"sex"`
	Role      string `json:"role"`

	StudentNumber *int64  `json:"student_number"`
	Major         *string `json:"major"`
	Specialty     *string `json:"specialty"`
	GithubID      *string `json:"github_id"`
}

type webhookEventData struct {
	Old []indexedEventObject `json:"old"`
	New []indexedEventObject `json:"new"`
}

type indexedEventObject struct {
	Index  int             `json:"index"`
	Object json.RawMessage `json:"object"`
}

// userSyncMessage is consumed by cowork-user's Kafka SyncHandler.
// event_type drives a targeted update there (partial role change, not a full upsert).
type userSyncMessage struct {
	EventType     string  `json:"event_type"`
	EventID       string  `json:"event_id"`
	EventIndex    int     `json:"event_index"`
	OccurredAt    string  `json:"occurred_at"`
	Email         string  `json:"email"`
	Name          string  `json:"name"`
	Sex           string  `json:"sex"`
	StudentRole   string  `json:"student_role"`
	StudentNumber *int64  `json:"student_number"`
	Major         *string `json:"major"`
	Specialty     *string `json:"specialty"`
	GithubID      *string `json:"github_id"`
	DataGSMRefID  int64   `json:"datagsm_student_id"`
}

var supportedStudentEvents = map[string]struct{}{
	"student.updated": {},
}

type EventService struct {
	cfg           *config.AppConfig
	publisher     EventPublisher
	processedRepo ProcessedEventStore
}

func NewEventService(
	cfg *config.AppConfig,
	publisher EventPublisher,
	processedRepo ProcessedEventStore,
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

	providedBytes, err := hex.DecodeString(provided)
	if err != nil {
		return false
	}

	mac := hmac.New(sha256.New, []byte(s.cfg.DataGSMWebhookSecret))
	mac.Write(body)
	expected := mac.Sum(nil)

	return hmac.Equal(expected, providedBytes)
}

// ProcessEvent parses the verified webhook body and forwards student.updated
// changes to the user sync stream. Idempotent on the event id.
func (s *EventService) ProcessEvent(ctx context.Context, body []byte) error {
	var envelope WebhookEvent
	if err := json.Unmarshal(body, &envelope); err != nil {
		log.Printf("failed to parse webhook envelope: %v", err)
		return fmt.Errorf("%w: failed to parse webhook envelope", ErrInvalidPayload)
	}
	if envelope.ID == "" || envelope.Event == "" {
		return fmt.Errorf("%w: missing id or event", ErrInvalidPayload)
	}
	occurredAt, err := time.Parse(time.RFC3339Nano, envelope.Timestamp)
	if err != nil {
		return fmt.Errorf("%w: timestamp must be RFC3339", ErrInvalidPayload)
	}
	// cowork-user persists this ordering token in MySQL DATETIME(6). Normalize at
	// the producer boundary so replay comparisons use the same precision.
	envelope.Timestamp = occurredAt.UTC().Truncate(time.Microsecond).Format(time.RFC3339Nano)

	if _, ok := supportedStudentEvents[envelope.Event]; !ok {
		log.Printf("ignoring unsupported webhook event: %s", envelope.Event)
		return nil
	}

	processed, err := s.processedRepo.Exists(envelope.ID)
	if err != nil {
		log.Printf("failed to check processed event %s: %v", envelope.ID, err)
		return fmt.Errorf("failed to check processed event state")
	}
	if processed {
		log.Printf("duplicate webhook event ignored: %s (%s)", envelope.ID, envelope.Event)
		return nil
	}

	messages, err := s.buildUserSyncMessages(envelope)
	if err != nil {
		return err
	}

	for _, msg := range messages {
		payload, err := json.Marshal(msg)
		if err != nil {
			log.Printf("failed to marshal sync message for %s: %v", envelope.ID, err)
			return fmt.Errorf("failed to marshal sync message: %w", err)
		}

		if err := s.publisher.Publish(ctx, strconv.FormatInt(msg.DataGSMRefID, 10), payload); err != nil {
			return fmt.Errorf("failed to publish sync message: %w", err)
		}
	}

	// 발행이 성공한 뒤에 기록해 메시지 유실을 방지한다(at-least-once).
	// 기록 실패는 메시지가 이미 발행됐으므로 치명적이지 않다(다음 동일 이벤트 재수신 시 중복 발행, 다운스트림 멱등).
	if _, err := s.processedRepo.MarkProcessed(envelope.ID, envelope.Event); err != nil {
		log.Printf("failed to record processed event %s after publish: %v", envelope.ID, err)
	}

	return nil
}

func (s *EventService) buildUserSyncMessages(envelope WebhookEvent) ([]userSyncMessage, error) {
	var data webhookEventData
	if err := json.Unmarshal(envelope.Data, &data); err != nil {
		log.Printf("failed to unmarshal student event data for %s: %v", envelope.ID, err)
		return nil, fmt.Errorf("%w: failed to parse student event data", ErrInvalidPayload)
	}
	if len(data.New) == 0 {
		return nil, fmt.Errorf("%w: event %s missing data.new", ErrInvalidPayload, envelope.ID)
	}

	messages := make([]userSyncMessage, 0, len(data.New))
	for _, item := range data.New {
		if isEmptyObject(item.Object) {
			log.Printf("skipping empty student.updated new object for event %s index %d", envelope.ID, item.Index)
			continue
		}

		var student studentEventData
		if err := json.Unmarshal(item.Object, &student); err != nil {
			log.Printf("failed to unmarshal student object for %s index %d: %v", envelope.ID, item.Index, err)
			return nil, fmt.Errorf("%w: failed to parse student object", ErrInvalidPayload)
		}
		if student.StudentID == 0 {
			return nil, fmt.Errorf("%w: event %s index %d missing student_id", ErrInvalidPayload, envelope.ID, item.Index)
		}
		if student.Name == "" || student.Email == "" || student.Sex == "" {
			return nil, fmt.Errorf("%w: event %s index %d missing required student field", ErrInvalidPayload, envelope.ID, item.Index)
		}
		if student.Role == "" {
			return nil, fmt.Errorf("%w: event %s index %d missing role", ErrInvalidPayload, envelope.ID, item.Index)
		}

		messages = append(messages, userSyncMessage{
			EventType:     envelope.Event,
			EventID:       envelope.ID,
			EventIndex:    item.Index,
			OccurredAt:    envelope.Timestamp,
			Email:         student.Email,
			Name:          student.Name,
			Sex:           student.Sex,
			StudentRole:   student.Role,
			StudentNumber: student.StudentNumber,
			Major:         student.Major,
			Specialty:     student.Specialty,
			GithubID:      student.GithubID,
			DataGSMRefID:  student.StudentID,
		})
	}

	if len(messages) == 0 {
		log.Printf("no student objects to publish for event %s", envelope.ID)
	}

	return messages, nil
}

func isEmptyObject(raw json.RawMessage) bool {
	var object map[string]json.RawMessage
	if err := json.Unmarshal(raw, &object); err != nil {
		return false
	}
	return len(object) == 0
}
