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

func TestValidateUserIdentityCommandForGitHubAndLink(t *testing.T) {
	login := "octo-cat"
	accountID := int64(7)
	gsm := false
	github := UserIdentityCommand{
		SchemaVersion: UserIdentityCommandSchemaVersion, OperationID: "0b0f4c8e-6f0e-4a8e-9d3b-1f0c2a9b7c11",
		IdempotencyKey: "operation", CommandType: UserIdentityCommandUpsert, Provider: "GITHUB", ProviderSubject: "583231",
		Name: "Octo Cat", Email: "octo@example.com", GithubLogin: &login, GSMEmailVerified: &gsm, OccurredAt: time.Date(2026, 11, 23, 0, 0, 0, 0, time.UTC),
	}
	if err := ValidateUserIdentityCommand(github); err != nil {
		t.Fatalf("GitHub login rejected: %v", err)
	}
	link := github
	link.CommandType, link.AccountID = UserIdentityCommandLink, &accountID
	if err := ValidateUserIdentityCommand(link); err != nil {
		t.Fatalf("GitHub link rejected: %v", err)
	}

	invalidLogin, zero := "-octo", int64(0)
	for name, mutate := range map[string]func(*UserIdentityCommand){
		"login subject is not numeric":   func(c *UserIdentityCommand) { c.ProviderSubject = "octo-cat" },
		"login subject has leading zero": func(c *UserIdentityCommand) { c.ProviderSubject = "0583231" },
		"login is missing":               func(c *UserIdentityCommand) { c.GithubLogin = nil },
		"school email flag is missing":   func(c *UserIdentityCommand) { c.GSMEmailVerified = nil },
		"login is invalid":               func(c *UserIdentityCommand) { c.GithubLogin = &invalidLogin },
		"student is attached":            func(c *UserIdentityCommand) { c.Student = &StudentIdentity{} },
		"upsert names an account":        func(c *UserIdentityCommand) { c.AccountID = &accountID },
		"link has no account":            func(c *UserIdentityCommand) { c.CommandType = UserIdentityCommandLink },
		"link names account zero":        func(c *UserIdentityCommand) { c.CommandType, c.AccountID = UserIdentityCommandLink, &zero },
		"link uses developer provider": func(c *UserIdentityCommand) {
			c.CommandType, c.AccountID, c.Provider, c.ProviderSubject, c.GithubLogin, c.GSMEmailVerified = UserIdentityCommandLink, &accountID, "DEV", "dev-1", nil, nil
		},
		"developer has GitHub login": func(c *UserIdentityCommand) { c.Provider, c.ProviderSubject = "DEV", "dev-1" },
		"developer has school email flag": func(c *UserIdentityCommand) {
			c.Provider, c.ProviderSubject, c.GithubLogin = "DEV", "dev-1", nil
		},
	} {
		command := github
		mutate(&command)
		if err := ValidateUserIdentityCommand(command); err == nil {
			t.Errorf("%s: command accepted", name)
		}
	}
}

func TestValidateUserIdentityCommandAcceptsADataGSMLinkWithStudent(t *testing.T) {
	accountID, studentID := int64(7), int64(501)
	link := UserIdentityCommand{
		SchemaVersion: UserIdentityCommandSchemaVersion, OperationID: "0b0f4c8e-6f0e-4a8e-9d3b-1f0c2a9b7c11",
		IdempotencyKey: "operation", CommandType: UserIdentityCommandLink, AccountID: &accountID, Provider: "DATAGSM", ProviderSubject: "99",
		Name: "Student", Email: "student@gsm.hs.kr", Student: &StudentIdentity{Sex: "MAN", Role: "GENERAL_STUDENT", DataGSMStudentID: &studentID},
		OccurredAt: time.Date(2026, 11, 23, 0, 0, 0, 0, time.UTC),
	}
	if err := ValidateUserIdentityCommand(link); err != nil {
		t.Fatalf("DataGSM link rejected: %v", err)
	}
	link.Student = nil
	if err := ValidateUserIdentityCommand(link); err == nil {
		t.Fatal("DataGSM link without student accepted")
	}
}
