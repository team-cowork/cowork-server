package mysql

import (
	"context"
	"database/sql"
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

const migrationDir = "/app/db/migration"

type migration struct {
	version     int
	versionText string
	description string
	script      string
	contents    string
}

func Migrate(ctx context.Context, db *gorm.DB, dsn string) error {
	if err := ensureHistoryTable(ctx, db); err != nil {
		return err
	}

	migrations, err := loadMigrations(migrationDir)
	if err != nil {
		return err
	}

	if err := baselineLegacySchema(ctx, db, migrations); err != nil {
		return err
	}

	applied, err := appliedVersions(ctx, db)
	if err != nil {
		return err
	}

	dbName, err := parseDatabaseName(dsn)
	if err != nil {
		return err
	}

	for _, m := range migrations {
		if applied[m.versionText] {
			continue
		}

		tx := db.WithContext(ctx).Begin()
		if tx.Error != nil {
			return tx.Error
		}

		for _, stmt := range splitSQLStatements(m.contents) {
			if err := tx.Exec(stmt).Error; err != nil {
				tx.Rollback()
				return fmt.Errorf("failed to execute migration %s: %w", m.script, err)
			}
		}

		if err := tx.Exec(
			`INSERT INTO flyway_schema_history
				(installed_rank, version, description, type, script, installed_by, execution_time, success)
			 VALUES
				((SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history fh), ?, ?, 'SQL', ?, USER(), 0, TRUE)`,
			m.versionText, m.description, m.script,
		).Error; err != nil {
			tx.Rollback()
			return fmt.Errorf("failed to record migration %s for %s: %w", m.script, dbName, err)
		}

		if err := tx.Commit().Error; err != nil {
			return fmt.Errorf("failed to commit migration %s: %w", m.script, err)
		}
	}

	return nil
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

	return insertBaselineRows(ctx, db, migrations)
}

func insertBaselineRows(ctx context.Context, db *gorm.DB, migrations []migration) error {
	tx := db.WithContext(ctx).Begin()
	if tx.Error != nil {
		return tx.Error
	}

	for index, m := range migrations {
		if err := tx.Exec(
			`INSERT INTO flyway_schema_history
				(installed_rank, version, description, type, script, installed_by, installed_on, execution_time, success)
			 VALUES
				(?, ?, ?, 'SQL', ?, USER(), ?, 0, TRUE)`,
			index+1, m.versionText, m.description, m.script, time.Now(),
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
	defer rows.Close()

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
