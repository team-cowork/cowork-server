package domain

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

var ErrIdentityResultRejected = errors.New("identity command result permanently rejected")

const (
	UserIdentitySchemaVersion = 1
	UserIdentityCommandUpsert = "UPSERT"
	UserIdentityPending       = "PENDING"
	UserIdentitySucceeded     = "SUCCEEDED"
	UserIdentityFailed        = "FAILED"

	MaxIdentityIdempotencyKeyLength = 128
	MaxIdentityErrorCodeLength      = 64
	MaxIdentityErrorMessageLength   = 500
)

// UserIdentityCommand is the durable owner command that replaces the former synchronous HTTP upsert.
// Authorization submits it; cowork-user commits the owned account/profile state.
type UserIdentityCommand struct {
	SchemaVersion        int       `json:"schemaVersion"`
	OperationID          string    `json:"operationId"`
	IdempotencyKey       string    `json:"idempotencyKey"`
	CommandType          string    `json:"commandType"`
	UserID               int64     `json:"userId"`
	Name                 string    `json:"name"`
	Email                string    `json:"email"`
	Sex                  string    `json:"sex"`
	Grade                *int8     `json:"grade"`
	ClassNumber          *int8     `json:"classNumber"`
	StudentNumberInClass *int8     `json:"studentNumberInClass"`
	Major                string    `json:"major"`
	Role                 string    `json:"role"`
	GithubID             *string   `json:"githubId"`
	DataGSMStudentID     *int64    `json:"dataGSMStudentId"`
	RequestedBy          int64     `json:"requestedBy"`
	OccurredAt           time.Time `json:"occurredAt"`
}

type UserIdentityCommandError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type UserIdentityCommandResult struct {
	SchemaVersion int                       `json:"schemaVersion"`
	OperationID   string                    `json:"operationId"`
	Status        string                    `json:"status"`
	UserID        *int64                    `json:"userId,omitempty"`
	Error         *UserIdentityCommandError `json:"error,omitempty"`
	OccurredAt    time.Time                 `json:"occurredAt"`
}

type UserIdentityOperation struct {
	OperationID    string     `gorm:"column:operation_id;primaryKey"`
	IdempotencyKey string     `gorm:"column:idempotency_key;not null"`
	UserID         int64      `gorm:"column:user_id;not null"`
	RequestHash    string     `gorm:"column:request_hash;not null"`
	Status         string     `gorm:"column:status;not null"`
	ResultUserID   *int64     `gorm:"column:result_user_id"`
	ErrorCode      *string    `gorm:"column:error_code"`
	ErrorMessage   *string    `gorm:"column:error_message"`
	ResultHash     *string    `gorm:"column:result_hash"`
	CreatedAt      time.Time  `gorm:"column:created_at;autoCreateTime:nano"`
	UpdatedAt      time.Time  `gorm:"column:updated_at;autoUpdateTime:nano"`
	CompletedAt    *time.Time `gorm:"column:completed_at"`
}

func (UserIdentityOperation) TableName() string {
	return "tb_user_identity_operations"
}

func NewUUID() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	return fmt.Sprintf(
		"%s-%s-%s-%s-%s",
		hex.EncodeToString(bytes[0:4]),
		hex.EncodeToString(bytes[4:6]),
		hex.EncodeToString(bytes[6:8]),
		hex.EncodeToString(bytes[8:10]),
		hex.EncodeToString(bytes[10:16]),
	), nil
}

func ValidateUserIdentityCommand(command UserIdentityCommand) error {
	if command.SchemaVersion != UserIdentitySchemaVersion {
		return fmt.Errorf("schemaVersion must be 1")
	}
	if !ValidUUID(command.OperationID) {
		return fmt.Errorf("operationId must be a canonical UUID")
	}
	if strings.TrimSpace(command.IdempotencyKey) == "" ||
		utf8.RuneCountInString(command.IdempotencyKey) > MaxIdentityIdempotencyKeyLength {
		return fmt.Errorf("idempotencyKey must contain at most %d characters", MaxIdentityIdempotencyKeyLength)
	}
	if command.CommandType != UserIdentityCommandUpsert {
		return fmt.Errorf("commandType must be UPSERT")
	}
	if command.UserID <= 0 || command.RequestedBy != command.UserID {
		return fmt.Errorf("userId and requestedBy must be the same positive value")
	}
	for _, field := range []struct {
		name  string
		value string
		max   int
	}{
		{name: "name", value: command.Name, max: 50},
		{name: "email", value: command.Email, max: 255},
		{name: "sex", value: command.Sex, max: 10},
		{name: "major", value: command.Major, max: 50},
		{name: "role", value: command.Role, max: 50},
	} {
		if strings.TrimSpace(field.value) == "" {
			return fmt.Errorf("%s must not be blank", field.name)
		}
		if utf8.RuneCountInString(field.value) > field.max {
			return fmt.Errorf("%s exceeds %d characters", field.name, field.max)
		}
	}
	if command.GithubID != nil && utf8.RuneCountInString(*command.GithubID) > 100 {
		return fmt.Errorf("githubId exceeds 100 characters")
	}
	if command.DataGSMStudentID != nil && *command.DataGSMStudentID <= 0 {
		return fmt.Errorf("dataGSMStudentId must be positive")
	}
	if command.OccurredAt.IsZero() {
		return fmt.Errorf("occurredAt must not be zero")
	}
	return nil
}

func ValidateUserIdentityCommandResult(result UserIdentityCommandResult) error {
	if result.SchemaVersion != UserIdentitySchemaVersion {
		return fmt.Errorf("schemaVersion must be 1")
	}
	if !ValidUUID(result.OperationID) {
		return fmt.Errorf("operationId must be a canonical UUID")
	}
	if result.OccurredAt.IsZero() {
		return fmt.Errorf("occurredAt must not be zero")
	}
	switch result.Status {
	case UserIdentitySucceeded:
		if result.UserID == nil || *result.UserID <= 0 || result.Error != nil {
			return fmt.Errorf("SUCCEEDED requires a positive userId and no error")
		}
	case UserIdentityFailed:
		if result.UserID != nil || result.Error == nil {
			return fmt.Errorf("FAILED requires an error and no userId")
		}
		if strings.TrimSpace(result.Error.Code) == "" ||
			utf8.RuneCountInString(result.Error.Code) > MaxIdentityErrorCodeLength {
			return fmt.Errorf("error.code is invalid")
		}
		if strings.TrimSpace(result.Error.Message) == "" ||
			utf8.RuneCountInString(result.Error.Message) > MaxIdentityErrorMessageLength {
			return fmt.Errorf("error.message is invalid")
		}
	default:
		return fmt.Errorf("status must be SUCCEEDED or FAILED")
	}
	return nil
}

func MarshalCanonical(value any) ([]byte, error) {
	return json.Marshal(value)
}

func ValidUUID(value string) bool {
	if len(value) != 36 || value[8] != '-' || value[13] != '-' || value[18] != '-' || value[23] != '-' {
		return false
	}
	compact := strings.ReplaceAll(value, "-", "")
	if compact != strings.ToLower(compact) {
		return false
	}
	decoded, err := hex.DecodeString(compact)
	return err == nil && len(decoded) == 16
}
