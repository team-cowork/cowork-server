package monitoring

import (
	"database/sql"
	"os"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
)

func RegisterSQLite(db *sql.DB, path string) {
	prometheus.MustRegister(
		collectors.NewDBStatsCollector(db, "notification"),
		sqliteFileSize("db", path),
		sqliteFileSize("wal", path+"-wal"),
	)
}

func sqliteFileSize(file, path string) prometheus.GaugeFunc {
	return prometheus.NewGaugeFunc(
		prometheus.GaugeOpts{
			Name:        "cowork_notification_sqlite_file_size_bytes",
			Help:        "On-disk size of the SQLite database file and its write-ahead log.",
			ConstLabels: prometheus.Labels{"file": file},
		},
		func() float64 {
			info, err := os.Stat(path)
			if err != nil {
				return 0
			}
			return float64(info.Size())
		},
	)
}
