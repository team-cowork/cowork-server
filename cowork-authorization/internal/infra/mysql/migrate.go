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
	migrationLockName           = "cowork-authorization-schema-migration"
	migrationLockWaitSeconds    = 60
	migrationLockReleaseTimeout = 5 * time.Second
	presenceStateBackfillQuery  = `SELECT COUNT(*)
		 FROM (
		     SELECT user_id,
		            SUM(expires_at > UTC_TIMESTAMP(6)) AS expected_active_sessions
		     FROM tb_refresh_tokens
		     GROUP BY user_id
		 ) AS token_user
		 LEFT JOIN tb_user_presence_states AS presence ON presence.user_id = token_user.user_id
		 WHERE presence.user_id IS NULL
		    OR presence.active_session_count <> token_user.expected_active_sessions
		    OR presence.status <>
		       IF(token_user.expected_active_sessions > 0, 'online', 'offline')`
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
	if configured := strings.TrimSpace(os.Getenv("DB_MIGRATION_DIR")); configured != "" {
		if isDirectory(configured) {
			return configured, nil
		}
		return "", fmt.Errorf("configured DB_MIGRATION_DIR is not a directory: %s", configured)
	}
	for _, candidate := range []string{containerMigrationDir, localMigrationDir} {
		if isDirectory(candidate) {
			return candidate, nil
		}
	}
	return "", fmt.Errorf(
		"database migration directory not found (checked %s and %s)",
		containerMigrationDir,
		localMigrationDir,
	)
}

func isDirectory(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
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

	hasRefreshTokens, err := tableExists(ctx, db, "tb_refresh_tokens")
	if err != nil || !hasRefreshTokens {
		return err
	}

	hasUsers, err := tableExists(ctx, db, "tb_users")
	if err != nil {
		return err
	}
	hasOauth2, err := tableExists(ctx, db, "tb_oauth2_connections")
	if err != nil {
		return err
	}
	hasEmailColumn, err := columnExists(ctx, db, "tb_refresh_tokens", "email")
	if err != nil {
		return err
	}
	hasGsmRoleColumn, err := columnExists(ctx, db, "tb_refresh_tokens", "gsm_role")
	if err != nil {
		return err
	}

	if hasUsers || hasOauth2 || !hasEmailColumn || !hasGsmRoleColumn {
		return nil
	}

	baselineVersions := map[string]bool{"1": true, "2": true}
	hasProcessedEvents, err := tableExists(ctx, db, "tb_processed_events")
	if err != nil {
		return err
	}
	if hasProcessedEvents {
		baselineVersions["3"] = true
	}
	hasKafkaOutbox, err := tableExists(ctx, db, "tb_kafka_outbox")
	if err != nil {
		return err
	}
	if hasKafkaOutbox {
		baselineVersions["4"] = true
		hasPartitionID, err := columnExists(ctx, db, "tb_kafka_outbox", "partition_id")
		if err != nil {
			return err
		}
		if hasPartitionID {
			baselineVersions["5"] = true
		}
	}
	hasPresenceStates, err := tableExists(ctx, db, "tb_user_presence_states")
	if err != nil {
		return err
	}
	if hasPresenceStates {
		complete, _, err := migrationSchemaComplete(ctx, db, 6)
		if err != nil {
			return err
		}
		if complete {
			baselineVersions["6"] = true
		}
	}
	hasPlatformRole, err := columnExists(ctx, db, "tb_refresh_tokens", "platform_role")
	if err != nil {
		return err
	}
	if hasPlatformRole {
		baselineVersions["7"] = true
	}

	return insertBaselineRows(ctx, db, migrations, baselineVersions)
}

func insertBaselineRows(
	ctx context.Context,
	db *gorm.DB,
	migrations []migration,
	baselineVersions map[string]bool,
) error {
	tx := db.WithContext(ctx).Begin()
	if tx.Error != nil {
		return tx.Error
	}

	installedRank := 0
	for _, m := range migrations {
		if !baselineVersions[m.versionText] {
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
	absent  bool
}

func migrationExpectations(version int) ([]tableExpectation, bool) {
	switch version {
	case 1:
		return []tableExpectation{
			{
				name:    "tb_users",
				columns: []string{"id", "email", "role", "is_active", "created_at", "updated_at"},
			},
			{
				name: "tb_refresh_tokens",
				columns: []string{
					"id", "user_id", "token_hash", "device_info", "expires_at", "created_at",
				},
			},
			{
				name: "tb_oauth2_connections",
				columns: []string{
					"id", "user_id", "provider", "provider_user_id", "created_at",
				},
			},
		}, true
	case 2:
		return []tableExpectation{
			{
				name: "tb_refresh_tokens",
				columns: []string{
					"id", "user_id", "token_hash", "email", "gsm_role", "device_info", "expires_at", "created_at",
				},
			},
			{name: "tb_users", absent: true},
			{name: "tb_oauth2_connections", absent: true},
		}, true
	case 3:
		return []tableExpectation{
			{name: "tb_processed_events", columns: []string{"event_id", "event_type", "created_at"}},
		}, true
	case 4:
		return []tableExpectation{
			{
				name: "tb_kafka_outbox",
				columns: []string{
					"id", "topic", "event_key", "payload", "attempts", "last_error", "created_at",
				},
			},
		}, true
	case 5:
		return []tableExpectation{
			{
				name: "tb_kafka_outbox",
				columns: []string{
					"id", "topic", "partition_id", "event_key", "payload", "attempts", "last_error", "created_at",
				},
			},
		}, true
	case 6:
		return []tableExpectation{
			{
				name: "tb_user_presence_states",
				columns: []string{
					"user_id", "status", "active_session_count", "occurred_at", "created_at", "updated_at",
				},
			},
		}, true
	case 7:
		return []tableExpectation{
			{
				name: "tb_refresh_tokens",
				columns: []string{
					"id", "user_id", "token_hash", "email", "gsm_role", "platform_role",
					"device_info", "expires_at", "created_at",
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
		if expectation.absent {
			if exists {
				return false, true, nil
			}
			continue
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
	if version == 6 {
		complete, err := presenceStateBackfillComplete(ctx, db)
		if err != nil {
			return false, true, err
		}
		if !complete {
			return false, true, nil
		}
	}

	return true, true, nil
}

func presenceStateBackfillComplete(ctx context.Context, db *gorm.DB) (bool, error) {
	var mismatched int64
	err := db.WithContext(ctx).Raw(presenceStateBackfillQuery).Scan(&mismatched).Error
	return mismatched == 0, err
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
