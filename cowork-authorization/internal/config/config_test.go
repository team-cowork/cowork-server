package config

import (
	"testing"
	"time"

	mysqlDriver "github.com/go-sql-driver/mysql"
)

func TestNormalizeMySQLDSNUTCOverridesHostLocalTimeDeterministically(t *testing.T) {
	t.Parallel()

	normalized, err := normalizeMySQLDSNUTC(
		"user:password@tcp(localhost:3306)/cowork_authorization?charset=utf8mb4&parseTime=false&loc=Local",
	)
	if err != nil {
		t.Fatal(err)
	}

	cfg, err := mysqlDriver.ParseDSN(normalized)
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.ParseTime {
		t.Fatal("ParseTime = false, want true")
	}
	if cfg.Loc != time.UTC {
		t.Fatalf("Loc = %v, want UTC", cfg.Loc)
	}
	if got := cfg.Params["time_zone"]; got != "'+00:00'" {
		t.Fatalf("time_zone = %q, want quoted +00:00", got)
	}
}

func TestNormalizeMySQLDSNUTCRejectsMalformedDSN(t *testing.T) {
	t.Parallel()

	if _, err := normalizeMySQLDSNUTC("not-a-dsn"); err == nil {
		t.Fatal("normalizeMySQLDSNUTC() error = nil, want malformed DSN failure")
	}
}
