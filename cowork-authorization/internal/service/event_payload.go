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

	"github.com/tmp-it-play/datagsm-sdk-go/event"
)

// Keep this projection stable: existing inbox hashes use these fields and their order.
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

func parseProviderEvent(body []byte, signature, secret string) (*event.Event, error) {
	if err := event.VerifySignature(body, signature, secret); err != nil {
		return nil, err
	}
	var envelope struct {
		ID        string     `json:"id"`
		Type      event.Type `json:"event"`
		Timestamp time.Time  `json:"timestamp"`
	}
	if !utf8.Valid(body) || uniqueJSONKeys(body) != nil || json.Unmarshal(body, &envelope) != nil ||
		!requiredString(envelope.ID, 255) || len(envelope.ID) > 255 || strings.TrimSpace(envelope.ID) != envelope.ID ||
		!requiredString(string(envelope.Type), 64) || envelope.Timestamp.Year() < 1000 || envelope.Timestamp.Year() > 9999 {
		return nil, ErrInvalidPayload
	}
	// Unsubscribed event payloads may use schemas this SDK version does not know.
	if envelope.Type != event.StudentUpdated {
		return &event.Event{ID: envelope.ID, Type: envelope.Type, Timestamp: envelope.Timestamp}, nil
	}
	received, err := event.Parse(body, signature, secret)
	if err != nil {
		return nil, ErrInvalidPayload
	}
	return received, nil
}

func studentItems(received *event.Event) ([]studentItem, error) {
	changes, err := event.Changes[event.Student](received)
	if err != nil || len(changes) == 0 {
		return nil, ErrInvalidPayload
	}
	items := make([]studentItem, 0, len(changes))
	students := make(map[int64]bool, len(changes))
	for _, change := range changes {
		if change.New == nil {
			return nil, ErrInvalidPayload
		}
		st := change.New
		student := studentEventData{StudentID: st.ID, Name: st.Name, Email: st.Email,
			Sex: string(st.Sex), Role: string(st.Role), Specialty: st.Specialty, GithubID: st.GitHubID}
		if st.StudentNumber != nil {
			n := int64(*st.StudentNumber)
			student.StudentNumber = &n
		}
		if st.Major != nil {
			major := string(*st.Major)
			student.Major = &major
		}
		if !validStudent(student) || students[st.ID] {
			return nil, ErrInvalidPayload
		}
		students[st.ID] = true
		items = append(items, studentItem{Index: int64(change.Index), Object: &student})
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

func studentMessages(envelope *event.Event, items []studentItem) []userSyncMessage {
	messages := make([]userSyncMessage, 0, len(items))
	for _, item := range items {
		student := item.Object
		if student == nil {
			continue
		}
		messages = append(messages, userSyncMessage{
			EventType: string(envelope.Type), EventID: envelope.ID, EventIndex: item.Index, OccurredAt: envelope.Timestamp.UTC().Truncate(time.Microsecond).Format(time.RFC3339Nano),
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
			// encoding/json accepts case-insensitive aliases; provider identity fields are exact.
			switch strings.ToLower(key) {
			case "id", "event", "timestamp", "data", "old", "new", "index", "object", "student_id", "name", "email", "sex", "role", "student_number", "major", "specialty", "github_id":
				if key != strings.ToLower(key) {
					return ErrInvalidPayload
				}
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
