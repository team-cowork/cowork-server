package service

import (
	"bytes"
	"encoding/json"
	"io"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

type WebhookEvent struct {
	ID        string          `json:"id"`
	Event     string          `json:"event"`
	Timestamp string          `json:"timestamp"`
	Data      json.RawMessage `json:"data"`
}

type studentEventData struct {
	StudentID     int64   `json:"student_id"`
	Name          string  `json:"name"`
	Email         string  `json:"email"`
	Sex           string  `json:"sex"`
	Role          string  `json:"role"`
	StudentNumber *int64  `json:"student_number"`
	Major         *string `json:"major"`
	Specialty     *string `json:"specialty"`
	GithubID      *string `json:"github_id"`
}

type studentItem struct {
	Index  int64             `json:"index"`
	Object *studentEventData `json:"object"`
}

type userSyncMessage struct {
	EventType     string  `json:"event_type"`
	EventID       string  `json:"event_id"`
	EventIndex    int64   `json:"event_index"`
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

func parseWebhookEnvelope(body []byte) (WebhookEvent, time.Time, error) {
	var envelope WebhookEvent
	if !utf8.Valid(body) || uniqueJSONKeys(body) != nil {
		return envelope, time.Time{}, ErrInvalidPayload
	}
	if decodeKnownObject(body, &envelope, "id", "event", "timestamp", "data") != nil ||
		!requiredString(envelope.ID, 255) || len(envelope.ID) > 255 || strings.TrimSpace(envelope.ID) != envelope.ID ||
		!requiredString(envelope.Event, 64) {
		return envelope, time.Time{}, ErrInvalidPayload
	}
	occurredAt, err := time.Parse(time.RFC3339Nano, envelope.Timestamp)
	if err != nil || occurredAt.Year() < 1000 || occurredAt.Year() > 9999 {
		return envelope, time.Time{}, ErrInvalidPayload
	}
	return envelope, occurredAt.UTC(), nil
}

// decodeKnownObject selects exact, case-sensitive provider field names. Unknown
// metadata is ignored; encoding/json's case-insensitive struct matching is not
// allowed to reinterpret another field as an identity or a business value.
func decodeKnownObject(raw []byte, target any, names ...string) error {
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil || fields == nil {
		return ErrInvalidPayload
	}
	selected := make(map[string]json.RawMessage, len(names))
	for _, name := range names {
		if value, ok := fields[name]; ok {
			selected[name] = value
		}
	}
	encoded, err := json.Marshal(selected)
	if err != nil || json.Unmarshal(encoded, target) != nil {
		return ErrInvalidPayload
	}
	return nil
}

func parseStudentItems(raw []byte) ([]studentItem, error) {
	var data struct {
		New []json.RawMessage `json:"new"`
	}
	if uniqueJSONKeys(raw) != nil || decodeKnownObject(raw, &data, "new") != nil || len(data.New) == 0 {
		return nil, ErrInvalidPayload
	}
	items := make([]studentItem, 0, len(data.New))
	indices := make(map[int64]struct{}, len(data.New))
	students := make(map[int64]struct{}, len(data.New))
	for _, rawItem := range data.New {
		var item struct {
			Index  *int64          `json:"index"`
			Object json.RawMessage `json:"object"`
		}
		if decodeKnownObject(rawItem, &item, "index", "object") != nil || item.Index == nil || *item.Index < 0 {
			return nil, ErrInvalidPayload
		}
		if _, exists := indices[*item.Index]; exists {
			return nil, ErrInvalidPayload
		}
		indices[*item.Index] = struct{}{}
		var object map[string]json.RawMessage
		if json.Unmarshal(item.Object, &object) != nil || object == nil {
			return nil, ErrInvalidPayload
		}
		if len(object) == 0 {
			items = append(items, studentItem{Index: *item.Index})
			continue
		}
		var student studentEventData
		if decodeKnownObject(item.Object, &student, "student_id", "name", "email", "sex", "role", "student_number", "major", "specialty", "github_id") != nil || !validStudent(student) {
			return nil, ErrInvalidPayload
		}
		if _, exists := students[student.StudentID]; exists {
			return nil, ErrInvalidPayload
		}
		students[student.StudentID] = struct{}{}
		items = append(items, studentItem{Index: *item.Index, Object: &student})
	}
	sort.Slice(items, func(i, j int) bool { return items[i].Index < items[j].Index })
	return items, nil
}

func requiredString(value string, limit int) bool {
	return strings.TrimSpace(value) != "" && utf8.RuneCountInString(value) <= limit
}

func optionalString(value *string, limit int) bool {
	return value == nil || utf8.RuneCountInString(*value) <= limit
}

func validStudent(student studentEventData) bool {
	return student.StudentID > 0 && requiredString(student.Name, 50) &&
		requiredString(student.Email, 255) && requiredString(student.Sex, 10) && requiredString(student.Role, 50) &&
		optionalString(student.Major, 50) && optionalString(student.Specialty, 255) && optionalString(student.GithubID, 100)
}

func studentMessages(envelope WebhookEvent, items []studentItem) []userSyncMessage {
	messages := make([]userSyncMessage, 0, len(items))
	for _, item := range items {
		student := item.Object
		if student == nil {
			continue
		}
		messages = append(messages, userSyncMessage{
			EventType: envelope.Event, EventID: envelope.ID, EventIndex: item.Index, OccurredAt: envelope.Timestamp,
			Email: student.Email, Name: student.Name, Sex: student.Sex, StudentRole: student.Role,
			StudentNumber: student.StudentNumber, Major: student.Major, Specialty: student.Specialty,
			GithubID: student.GithubID, DataGSMRefID: student.StudentID,
		})
	}
	return messages
}

func studentKey(id int64) string { return strconv.FormatInt(id, 10) }

// Reject duplicate keys before a map or struct can silently discard a value.
// A bounded nesting depth also protects the signed request parser from stack abuse.
func uniqueJSONKeys(raw []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err := readJSONValue(decoder, 0); err != nil {
		return ErrInvalidPayload
	}
	if _, err := decoder.Token(); err != io.EOF {
		return ErrInvalidPayload
	}
	return nil
}

func readJSONValue(decoder *json.Decoder, depth int) error {
	if depth > 64 {
		return ErrInvalidPayload
	}
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delim, isDelim := token.(json.Delim)
	if !isDelim {
		return nil
	}
	switch delim {
	case '{':
		seen := make(map[string]struct{})
		for decoder.More() {
			token, err := decoder.Token()
			if err != nil {
				return err
			}
			key, ok := token.(string)
			if !ok {
				return ErrInvalidPayload
			}
			if _, exists := seen[key]; exists {
				return ErrInvalidPayload
			}
			seen[key] = struct{}{}
			if err := readJSONValue(decoder, depth+1); err != nil {
				return err
			}
		}
	case '[':
		for decoder.More() {
			if err := readJSONValue(decoder, depth+1); err != nil {
				return err
			}
		}
	default:
		return ErrInvalidPayload
	}
	_, err = decoder.Token()
	return err
}
