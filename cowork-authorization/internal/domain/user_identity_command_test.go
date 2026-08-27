package domain

import (
	"testing"
	"time"
)

func TestUserIdentityCommandAndResultContractsAreStrict(t *testing.T) {
	t.Parallel()

	operationID := "00000000-0000-4000-8000-000000000001"
	studentID := int64(70)
	command := UserIdentityCommand{
		SchemaVersion:    1,
		OperationID:      operationID,
		IdempotencyKey:   operationID,
		CommandType:      UserIdentityCommandUpsert,
		UserID:           7,
		Name:             "User",
		Email:            "user@example.com",
		Sex:              "MAN",
		Major:            "SW_DEVELOPMENT",
		Role:             "GENERAL_STUDENT",
		DataGSMStudentID: &studentID,
		RequestedBy:      7,
		OccurredAt:       time.Now().UTC(),
	}
	if err := ValidateUserIdentityCommand(command); err != nil {
		t.Fatalf("valid command rejected: %v", err)
	}

	userID := int64(7)
	success := UserIdentityCommandResult{
		SchemaVersion: 1,
		OperationID:   operationID,
		Status:        UserIdentitySucceeded,
		UserID:        &userID,
		OccurredAt:    time.Now().UTC(),
	}
	if err := ValidateUserIdentityCommandResult(success); err != nil {
		t.Fatalf("valid success result rejected: %v", err)
	}
	success.Error = &UserIdentityCommandError{Code: "CONFLICT", Message: "conflict"}
	if err := ValidateUserIdentityCommandResult(success); err == nil {
		t.Fatal("contradictory success result was accepted")
	}
}
