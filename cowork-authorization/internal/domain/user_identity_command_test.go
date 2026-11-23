package domain

import (
	"testing"
	"time"
)

func TestValidateUserIdentityCommandResultAcceptsOnlyResultV2(t *testing.T) {
	userID := int64(7)
	email := "kim@gsm.hs.kr"
	role := "GENERAL_STUDENT"
	succeeded := UserIdentityCommandResult{
		SchemaVersion: UserIdentityResultSchemaVersion,
		OperationID:   "0b0f4c8e-6f0e-4a8e-9d3b-1f0c2a9b7c11",
		Status:        UserIdentitySucceeded,
		UserID:        &userID,
		Email:         &email,
		GsmRole:       &role,
		OccurredAt:    time.Date(2026, 11, 23, 0, 0, 0, 0, time.UTC),
	}
	if err := ValidateUserIdentityCommandResult(succeeded); err != nil {
		t.Fatalf("result v2 rejected: %v", err)
	}

	retired := succeeded
	retired.SchemaVersion = 1
	retired.Email, retired.GsmRole = nil, nil
	if err := ValidateUserIdentityCommandResult(retired); err == nil {
		t.Fatal("retired result v1 accepted")
	}

	withoutClaims := succeeded
	withoutClaims.Email = nil
	if err := ValidateUserIdentityCommandResult(withoutClaims); err == nil {
		t.Fatal("SUCCEEDED result without session claims accepted")
	}
}
