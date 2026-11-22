package domain

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

var ErrIdentityResultRejected = errors.New("identity command result permanently rejected")

const (
	UserIdentityCommandSchemaVersion = 3
	UserIdentityCommandUpsert        = "UPSERT"
	UserIdentityPending              = "PENDING"
	UserIdentitySucceeded            = "SUCCEEDED"
	UserIdentityFailed               = "FAILED"

	MaxIdentityIdempotencyKeyLength = 128
	MaxIdentityErrorCodeLength      = 64
	MaxIdentityErrorMessageLength   = 500
)

// UserIdentityCommand is the durable owner command that replaces the former synchronous HTTP upsert.
// Authorization submits it; cowork-user commits the owned account/profile state.
type UserIdentityCommand struct {
	SchemaVersion   int              `json:"schemaVersion"`
	OperationID     string           `json:"operationId"`
	IdempotencyKey  string           `json:"idempotencyKey"`
	CommandType     string           `json:"commandType"`
	Provider        string           `json:"provider"`
	ProviderSubject string           `json:"providerSubject"`
	Name            string           `json:"name"`
	Email           string           `json:"email"`
	Student         *StudentIdentity `json:"student"`
	OccurredAt      time.Time        `json:"occurredAt"`
}

type StudentIdentity struct {
	Sex                  string  `json:"sex"`
	Grade                *int8   `json:"grade"`
	ClassNumber          *int8   `json:"classNumber"`
	StudentNumberInClass *int8   `json:"studentNumberInClass"`
	Major                *string `json:"major"`
	Role                 string  `json:"role"`
	GithubID             *string `json:"githubId"`
	DataGSMStudentID     *int64  `json:"dataGSMStudentId"`
}

type SessionIdentity struct {
	UserID  int64
	Email   string
	GsmRole string
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
	Email         *string                   `json:"email,omitempty"`
	GsmRole       *string                   `json:"gsmRole,omitempty"`
	Error         *UserIdentityCommandError `json:"error,omitempty"`
	OccurredAt    time.Time                 `json:"occurredAt"`
}

type UserIdentityOperation struct {
	OperationID    string     `gorm:"column:operation_id;primaryKey"`
	IdempotencyKey string     `gorm:"column:idempotency_key;not null"`
	LegacyUserID   *int64     `gorm:"column:user_id"`
	ResultEmail    *string    `gorm:"column:result_email"`
	ResultGsmRole  *string    `gorm:"column:result_gsm_role"`
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
	if command.SchemaVersion != UserIdentityCommandSchemaVersion {
		return fmt.Errorf("command schemaVersion must be 3")
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
	if !validText(command.Name, 50) || !validText(command.Email, 255) {
		return errors.New("invalid account profile")
	}
	if command.OccurredAt.IsZero() {
		return errors.New("occurredAt must not be zero")
	}
	if command.Provider == "DEV" && command.Student == nil {
		switch command.ProviderSubject {
		case "dev-1", "dev-2", "dev-3", "dev-4":
			return nil
		}
	}
	if command.Provider != "DATAGSM" {
		return errors.New("unsupported identity provider")
	}
	id, err := strconv.ParseInt(command.ProviderSubject, 10, 64)
	st := command.Student
	if err != nil || id <= 0 || strconv.FormatInt(id, 10) != command.ProviderSubject || st == nil ||
		st.DataGSMStudentID == nil || *st.DataGSMStudentID <= 0 || !validText(st.Sex, 10) || !validText(st.Role, 50) ||
		(st.Major != nil && utf8.RuneCountInString(*st.Major) > 50) || (st.GithubID != nil && utf8.RuneCountInString(*st.GithubID) > 100) {
		return errors.New("invalid DataGSM identity")
	}
	return nil
}

func validText(value string, max int) bool {
	return strings.TrimSpace(value) != "" && utf8.RuneCountInString(value) <= max
}

func ValidateUserIdentityCommandResult(result UserIdentityCommandResult) error {
	if result.SchemaVersion != 1 && result.SchemaVersion != 2 {
		return fmt.Errorf("unsupported result schemaVersion")
	}
	if !ValidUUID(result.OperationID) {
		return fmt.Errorf("operationId must be a canonical UUID")
	}
	if result.OccurredAt.IsZero() {
		return fmt.Errorf("occurredAt must not be zero")
	}
	needsClaims := result.SchemaVersion == 2 && result.Status == UserIdentitySucceeded
	if needsClaims {
		if result.Email == nil || !validText(*result.Email, 255) || result.GsmRole == nil || utf8.RuneCountInString(*result.GsmRole) > 50 {
			return errors.New("missing or invalid committed session claims")
		}
	} else if result.Email != nil || result.GsmRole != nil {
		return errors.New("unexpected session claims")
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
