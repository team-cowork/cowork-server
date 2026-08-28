package mysql

import (
	"errors"
	"fmt"
	"strings"
	"testing"

	mysqlDriver "github.com/go-sql-driver/mysql"
)

func TestResolveMigrationDirUsesLocalSourceTreeForHostRun(t *testing.T) {
	t.Setenv("DB_MIGRATION_DIR", "")
	t.Chdir("../../..")

	dir, err := resolveMigrationDir()
	if err != nil {
		t.Fatalf("resolveMigrationDir() error = %v", err)
	}
	if dir != localMigrationDir {
		t.Fatalf("resolveMigrationDir() = %q, want %q", dir, localMigrationDir)
	}
}

func TestResolveMigrationDirRejectsInvalidExplicitOverride(t *testing.T) {
	t.Setenv("DB_MIGRATION_DIR", t.TempDir()+"/missing")

	if _, err := resolveMigrationDir(); err == nil {
		t.Fatal("resolveMigrationDir() error = nil, want invalid override failure")
	}
}

func TestIsRecoverableMigrationError(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name        string
		err         error
		recoverable bool
	}{
		{name: "table already exists", err: &mysqlDriver.MySQLError{Number: 1050}, recoverable: true},
		{name: "duplicate column", err: &mysqlDriver.MySQLError{Number: 1060}, recoverable: true},
		{name: "foreign key already absent", err: &mysqlDriver.MySQLError{Number: 1091}, recoverable: true},
		{
			name:        "wrapped recoverable error",
			err:         fmt.Errorf("execute statement: %w", &mysqlDriver.MySQLError{Number: 1050}),
			recoverable: true,
		},
		{name: "duplicate entry", err: &mysqlDriver.MySQLError{Number: 1062}, recoverable: false},
		{name: "non mysql error", err: errors.New("connection reset"), recoverable: false},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			if got := isRecoverableMigrationError(test.err); got != test.recoverable {
				t.Fatalf("isRecoverableMigrationError() = %v, want %v", got, test.recoverable)
			}
		})
	}
}

func TestMigrationExpectationsCoverCurrentVersions(t *testing.T) {
	t.Parallel()

	wantTableCounts := map[int]int{
		1: 3,
		2: 3,
		3: 1,
		4: 1,
		5: 1,
		6: 1,
		7: 1,
		8: 1,
	}
	for version, wantCount := range wantTableCounts {
		expectations, known := migrationExpectations(version)
		if !known {
			t.Fatalf("migration V%d is missing a recovery verifier", version)
		}
		if len(expectations) != wantCount {
			t.Fatalf("migration V%d expectations = %d tables, want %d", version, len(expectations), wantCount)
		}
	}

	if _, known := migrationExpectations(9); known {
		t.Fatal("unknown migration version must fail closed")
	}
}

func TestPresenceRecoveryVerifierChecksBackfilledStatusAndActiveCount(t *testing.T) {
	t.Parallel()

	for _, required := range []string{
		"expected_active_sessions",
		"presence.active_session_count <> token_user.expected_active_sessions",
		"IF(token_user.expected_active_sessions > 0, 'online', 'offline')",
	} {
		if !strings.Contains(presenceStateBackfillQuery, required) {
			t.Fatalf("presence recovery verifier is missing %q", required)
		}
	}
}
