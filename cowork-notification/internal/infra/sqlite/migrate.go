package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"gorm.io/gorm"
)

var migrationNamePattern = regexp.MustCompile(`^V(\d+)__(.+)\.sql$`)

const (
	containerMigrationDir = "/app/db/migration"
	localMigrationDir     = "src/main/resources/db/migration"
)

type migration struct {
	version     int
	versionText string
	description string
	script      string
	contents    string
}

// Migrate applies each pending migration in its own transaction. SQLite DDL is
// transactional, so a failed migration leaves no partial schema to recover.
func Migrate(ctx context.Context, db *gorm.DB) error {
	migrationDir, err := resolveMigrationDir()
	if err != nil {
		return err
	}
	migrations, err := loadMigrations(migrationDir)
	if err != nil {
		return err
	}

	if err := ensureHistoryTable(ctx, db); err != nil {
		return err
	}
	applied, err := appliedVersions(ctx, db)
	if err != nil {
		return err
	}

	for _, m := range migrations {
		if applied[m.versionText] {
			continue
		}
		if err := db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
			for _, stmt := range splitSQLStatements(m.contents) {
				if err := tx.Exec(stmt).Error; err != nil {
					return err
				}
			}
			return recordMigration(tx, m)
		}); err != nil {
			return fmt.Errorf("failed to apply migration %s: %w", m.script, err)
		}
	}
	return nil
}

func resolveMigrationDir() (string, error) {
	candidates := migrationDirCandidates(os.Getenv("DB_MIGRATION_DIR"))
	return firstExistingMigrationDir(candidates, func(candidate string) (bool, error) {
		info, err := os.Stat(candidate)
		if err != nil {
			return false, err
		}
		return info.IsDir(), nil
	})
}

func firstExistingMigrationDir(
	candidates []string,
	isDirectory func(string) (bool, error),
) (string, error) {
	for _, candidate := range candidates {
		exists, err := isDirectory(candidate)
		if err == nil && exists {
			return candidate, nil
		}
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return "", fmt.Errorf("inspect migration directory %s: %w", candidate, err)
		}
	}
	return "", fmt.Errorf("migration directory not found; checked %s", strings.Join(candidates, ", "))
}

func migrationDirCandidates(override string) []string {
	candidates := make([]string, 0, 3)
	if override = strings.TrimSpace(override); override != "" {
		candidates = append(candidates, override)
	}
	return append(candidates, containerMigrationDir, localMigrationDir)
}

func recordMigration(tx *gorm.DB, m migration) error {
	return tx.Exec(
		`INSERT INTO flyway_schema_history
			(installed_rank, version, description, type, script, installed_by, execution_time, success)
		 VALUES
			((SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history), ?, ?, 'SQL', ?, 'cowork-notification', 0, TRUE)`,
		m.versionText, m.description, m.script,
	).Error
}

func ensureHistoryTable(ctx context.Context, db *gorm.DB) error {
	return db.WithContext(ctx).Exec(`
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INTEGER NOT NULL PRIMARY KEY,
    version TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL,
    type TEXT NOT NULL,
    script TEXT NOT NULL,
    installed_by TEXT NOT NULL,
    installed_on DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time INTEGER NOT NULL,
    success BOOLEAN NOT NULL
)
`).Error
}

func loadMigrations(dir string) ([]migration, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}

	migrations := make([]migration, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}

		match := migrationNamePattern.FindStringSubmatch(entry.Name())
		if match == nil {
			continue
		}

		version, err := strconv.Atoi(match[1])
		if err != nil {
			return nil, err
		}

		bytes, err := os.ReadFile(dir + "/" + entry.Name())
		if err != nil {
			return nil, err
		}

		migrations = append(migrations, migration{
			version:     version,
			versionText: match[1],
			description: strings.ReplaceAll(match[2], "_", " "),
			script:      entry.Name(),
			contents:    string(bytes),
		})
	}

	sort.Slice(migrations, func(i, j int) bool {
		return migrations[i].version < migrations[j].version
	})
	return migrations, nil
}

func appliedVersions(ctx context.Context, db *gorm.DB) (map[string]bool, error) {
	rows, err := db.WithContext(ctx).Raw(
		`SELECT version FROM flyway_schema_history WHERE success = TRUE`,
	).Rows()
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	result := map[string]bool{}
	for rows.Next() {
		var version sql.NullString
		if err := rows.Scan(&version); err != nil {
			return nil, err
		}
		if version.Valid {
			result[version.String] = true
		}
	}
	return result, rows.Err()
}

func splitSQLStatements(contents string) []string {
	parts := strings.Split(contents, ";")
	statements := make([]string, 0, len(parts))
	for _, part := range parts {
		lines := strings.Split(part, "\n")
		filtered := make([]string, 0, len(lines))
		for _, line := range lines {
			trimmed := strings.TrimSpace(line)
			if trimmed == "" || strings.HasPrefix(trimmed, "--") {
				continue
			}
			filtered = append(filtered, line)
		}
		stmt := strings.TrimSpace(strings.Join(filtered, "\n"))
		if stmt != "" {
			statements = append(statements, stmt)
		}
	}
	return statements
}
