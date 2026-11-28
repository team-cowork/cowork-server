package mysql

import (
	"strings"
	"testing"
)

// Migrate stops at startup when a migration has no recovery verifier, so every script needs one
// that names only tables and columns the script creates.
func TestEveryMigrationHasSchemaExpectations(t *testing.T) {
	t.Parallel()

	migrations, err := loadMigrations("../../../src/main/resources/db/migration")
	if err != nil {
		t.Fatalf("loadMigrations() error = %v", err)
	}
	if len(migrations) == 0 {
		t.Fatal("loadMigrations() returned no migrations")
	}

	for _, m := range migrations {
		expectations, known := migrationExpectations(m.version)
		if !known {
			t.Errorf("migration %s has no schema expectations", m.script)
			continue
		}
		for _, table := range expectations {
			if !strings.Contains(m.contents, "CREATE TABLE `"+table.name+"`") {
				t.Errorf("migration %s does not create table %s", m.script, table.name)
			}
			for _, column := range table.columns {
				if !strings.Contains(m.contents, "`"+column+"`") {
					t.Errorf("migration %s does not define %s.%s", m.script, table.name, column)
				}
			}
		}
	}
}
