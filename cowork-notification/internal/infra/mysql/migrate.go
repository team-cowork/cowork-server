package mysql

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
	"time"

	mysqlDriver "github.com/go-sql-driver/mysql"
	"gorm.io/gorm"
)

var migrationNamePattern = regexp.MustCompile(`^V(\d+)__(.+)\.sql$`)

const (
	containerMigrationDir       = "/app/db/migration"
	localMigrationDir           = "src/main/resources/db/migration"
	migrationLockName           = "cowork-notification-schema-migration"
	migrationLockWaitSeconds    = 60
	migrationLockReleaseTimeout = 5 * time.Second
)

type migration struct {
	version     int
	versionText string
	description string
	script      string
	contents    string
}

func Migrate(ctx context.Context, db *gorm.DB, dsn string) error {
	migrationDir, err := resolveMigrationDir()
	if err != nil {
		return err
	}
	migrations, err := loadMigrations(migrationDir)
	if err != nil {
		return err
	}

	dbName, err := parseDatabaseName(dsn)
	if err != nil {
		return err
	}

	return db.WithContext(ctx).Connection(func(connectionDB *gorm.DB) (err error) {
		var acquired sql.NullInt64
		if err := connectionDB.WithContext(ctx).
			Raw(`SELECT GET_LOCK(?, ?)`, migrationLockName, migrationLockWaitSeconds).
			Row().Scan(&acquired); err != nil {
			return fmt.Errorf("failed to acquire migration lock for %s: %w", dbName, err)
		}
		if !acquired.Valid || acquired.Int64 != 1 {
			return fmt.Errorf("timed out acquiring migration lock for %s", dbName)
		}

		defer func() {
			releaseCtx, cancel := context.WithTimeout(context.Background(), migrationLockReleaseTimeout)
			defer cancel()

			var released sql.NullInt64
			releaseErr := connectionDB.WithContext(releaseCtx).
				Raw(`SELECT RELEASE_LOCK(?)`, migrationLockName).
				Row().Scan(&released)
			if err == nil && (releaseErr != nil || !released.Valid || released.Int64 != 1) {
				if releaseErr != nil {
					err = fmt.Errorf("failed to release migration lock for %s: %w", dbName, releaseErr)
				} else {
					err = fmt.Errorf("migration lock for %s was not owned by this connection", dbName)
				}
			}
		}()

		return migrateLocked(ctx, connectionDB, migrations, dbName)
	})
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

func migrateLocked(ctx context.Context, db *gorm.DB, migrations []migration, dbName string) error {
	if err := ensureHistoryTable(ctx, db); err != nil {
		return err
	}

	if err := baselineLegacySchema(ctx, db, migrations); err != nil {
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

		complete, known, err := migrationSchemaComplete(ctx, db, m.version)
		if err != nil {
			return fmt.Errorf("failed to inspect migration %s schema: %w", m.script, err)
		}
		if !known {
			return fmt.Errorf("migration %s has no schema recovery verifier", m.script)
		}

		if !complete {
			for _, stmt := range splitSQLStatements(m.contents) {
				if err := db.WithContext(ctx).Exec(stmt).Error; err != nil && !isRecoverableMigrationError(err) {
					return fmt.Errorf("failed to execute migration %s: %w", m.script, err)
				}
			}

			complete, _, err = migrationSchemaComplete(ctx, db, m.version)
			if err != nil {
				return fmt.Errorf("failed to verify migration %s schema: %w", m.script, err)
			}
			if !complete {
				return fmt.Errorf("migration %s did not produce its expected schema", m.script)
			}
		}

		if err := recordMigration(ctx, db, m); err != nil {
			return fmt.Errorf("failed to record migration %s for %s: %w", m.script, dbName, err)
		}
	}

	return nil
}

func recordMigration(ctx context.Context, db *gorm.DB, m migration) error {
	return db.WithContext(ctx).Exec(
		`INSERT INTO flyway_schema_history
			(installed_rank, version, description, type, script, installed_by, execution_time, success)
		 VALUES
			((SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history fh), ?, ?, 'SQL', ?, USER(), 0, TRUE)`,
		m.versionText, m.description, m.script,
	).Error
}

func ensureHistoryTable(ctx context.Context, db *gorm.DB) error {
	return db.WithContext(ctx).Exec(`
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL,
    version VARCHAR(50) NULL,
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    installed_by VARCHAR(100) NOT NULL,
    installed_on DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    execution_time INT NOT NULL,
    success TINYINT(1) NOT NULL,
    PRIMARY KEY (installed_rank),
    KEY idx_flyway_schema_history_success (success),
    UNIQUE KEY uq_flyway_schema_history_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
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

func baselineLegacySchema(ctx context.Context, db *gorm.DB, migrations []migration) error {
	count, err := historyCount(ctx, db)
	if err != nil || count > 0 {
		return err
	}

	hasTable, err := tableExists(ctx, db, "tb_device_token")
	if err != nil || !hasTable {
		return err
	}

	return insertLegacyBaseline(ctx, db, migrations)
}

func insertLegacyBaseline(ctx context.Context, db *gorm.DB, migrations []migration) error {
	tx := db.WithContext(ctx).Begin()
	if tx.Error != nil {
		return tx.Error
	}

	installedRank := 0
	for _, m := range migrations {
		// The schema predating Flyway contained only V1. New migrations must still
		// execute after that legacy schema is baselined.
		if m.version != 1 {
			continue
		}
		installedRank++
		if err := tx.Exec(
			`INSERT INTO flyway_schema_history
				(installed_rank, version, description, type, script, installed_by, installed_on, execution_time, success)
			 VALUES
				(?, ?, ?, 'SQL', ?, USER(), ?, 0, TRUE)`,
			installedRank, m.versionText, m.description, m.script, time.Now(),
		).Error; err != nil {
			tx.Rollback()
			return err
		}
	}

	return tx.Commit().Error
}

func appliedVersions(ctx context.Context, db *gorm.DB) (map[string]bool, error) {
	rows, err := db.WithContext(ctx).Raw(
		`SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL`,
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

func historyCount(ctx context.Context, db *gorm.DB) (int64, error) {
	var count int64
	err := db.WithContext(ctx).Raw(`SELECT COUNT(*) FROM flyway_schema_history`).Scan(&count).Error
	return count, err
}

func tableExists(ctx context.Context, db *gorm.DB, table string) (bool, error) {
	var count int64
	err := db.WithContext(ctx).Raw(
		`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?`,
		table,
	).Scan(&count).Error
	return count > 0, err
}

func columnExists(ctx context.Context, db *gorm.DB, table, column string) (bool, error) {
	var count int64
	err := db.WithContext(ctx).Raw(
		`SELECT COUNT(*) FROM information_schema.columns
		 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?`,
		table, column,
	).Scan(&count).Error
	return count > 0, err
}

type tableExpectation struct {
	name    string
	columns []string
}

func migrationExpectations(version int) ([]tableExpectation, bool) {
	switch version {
	case 1:
		return []tableExpectation{
			{
				name: "tb_device_token",
				columns: []string{
					"id", "account_id", "token", "platform", "created_at", "updated_at",
				},
			},
		}, true
	case 2:
		return []tableExpectation{
			{
				name: "tb_channel_notification_preferences",
				columns: []string{
					"account_id", "channel_id", "notification_enabled", "source_updated_at", "updated_at",
				},
			},
			{
				name: "tb_user_profile_projections",
				columns: []string{
					"user_id", "display_name", "deleted", "source_updated_at", "updated_at",
				},
			},
			{
				name: "tb_team_profile_projections",
				columns: []string{
					"team_id", "team_name", "deleted", "source_updated_at", "updated_at",
				},
			},
		}, true
	case 3:
		return []tableExpectation{
			{
				name: "tb_projection_checkpoints",
				columns: []string{
					"consumer_group", "topic_name", "partition_id", "next_offset", "created_at", "updated_at",
				},
			},
			{
				name: "tb_projection_dead_letters",
				columns: []string{
					"consumer_group", "topic_name", "partition_id", "message_offset", "event_key", "payload", "reason", "created_at",
				},
			},
		}, true
	case 4:
		return []tableExpectation{
			{
				name: "tb_projection_checkpoints",
				columns: []string{
					"consumer_group", "topic_name", "partition_id", "next_offset",
					"snapshot_completed_offset", "snapshot_id", "snapshot_source", "snapshot_occurred_at",
					"created_at", "updated_at",
				},
			},
		}, true
	default:
		return nil, false
	}
}

func migrationSchemaComplete(ctx context.Context, db *gorm.DB, version int) (bool, bool, error) {
	expectations, known := migrationExpectations(version)
	if !known {
		return false, false, nil
	}

	for _, expectation := range expectations {
		exists, err := tableExists(ctx, db, expectation.name)
		if err != nil {
			return false, true, err
		}
		if !exists {
			return false, true, nil
		}

		for _, column := range expectation.columns {
			exists, err := columnExists(ctx, db, expectation.name, column)
			if err != nil {
				return false, true, err
			}
			if !exists {
				return false, true, nil
			}
		}
	}

	return true, true, nil
}

func isRecoverableMigrationError(err error) bool {
	var mysqlErr *mysqlDriver.MySQLError
	if !errors.As(err, &mysqlErr) {
		return false
	}

	switch mysqlErr.Number {
	case 1050, // ER_TABLE_EXISTS_ERROR: a prior attempt completed CREATE TABLE.
		1060, // ER_DUP_FIELDNAME: a prior attempt completed ALTER TABLE ADD COLUMN.
		1091: // ER_CANT_DROP_FIELD_OR_KEY: a prior attempt completed DROP FOREIGN KEY.
		return true
	default:
		return false
	}
}

func parseDatabaseName(dsn string) (string, error) {
	cfg, err := mysqlDriver.ParseDSN(dsn)
	if err != nil {
		return "", fmt.Errorf("failed to parse dsn: %w", err)
	}
	if cfg.DBName == "" {
		return "", fmt.Errorf("database name missing from dsn")
	}
	return cfg.DBName, nil
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
