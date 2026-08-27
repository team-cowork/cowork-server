package mysql

import (
	"errors"
	"fmt"
	"reflect"
	"testing"

	mysqlDriver "github.com/go-sql-driver/mysql"
)

func TestMigrationDirCandidatesPreferOverrideThenDeploymentAndLocalPaths(t *testing.T) {
	t.Parallel()

	got := migrationDirCandidates(" /tmp/cowork-notification-migrations ")
	want := []string{
		"/tmp/cowork-notification-migrations",
		"/app/db/migration",
		"src/main/resources/db/migration",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("migrationDirCandidates() = %v, want %v", got, want)
	}
}

func TestMigrationDirCandidatesUseDeploymentAndLocalFallbacksWithoutOverride(t *testing.T) {
	t.Parallel()

	got := migrationDirCandidates("  ")
	want := []string{"/app/db/migration", "src/main/resources/db/migration"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("migrationDirCandidates() = %v, want %v", got, want)
	}
}

func TestFirstExistingMigrationDirFallsBackInCandidateOrder(t *testing.T) {
	t.Parallel()

	candidates := []string{"override", "container", "local"}
	got, err := firstExistingMigrationDir(candidates, func(candidate string) (bool, error) {
		return candidate == "local", nil
	})
	if err != nil {
		t.Fatalf("firstExistingMigrationDir() error = %v", err)
	}
	if got != "local" {
		t.Fatalf("firstExistingMigrationDir() = %q, want local", got)
	}
}

func TestResolveMigrationDirUsesExistingOverride(t *testing.T) {
	override := t.TempDir()
	t.Setenv("DB_MIGRATION_DIR", override)

	got, err := resolveMigrationDir()
	if err != nil {
		t.Fatalf("resolveMigrationDir() error = %v", err)
	}
	if got != override {
		t.Fatalf("resolveMigrationDir() = %q, want %q", got, override)
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
		1: 1,
		2: 3,
		3: 2,
		4: 1,
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

	if _, known := migrationExpectations(5); known {
		t.Fatal("unknown migration version must fail closed")
	}
}
