package logger

import (
	"io"
	"log/slog"
	"os"
)

var Default *slog.Logger

func Init(serviceName string) {
	logDir := "/var/log/cowork/" + serviceName
	_ = os.MkdirAll(logDir, 0755)

	logFile, err := os.OpenFile(logDir+"/app.log", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)

	var w io.Writer
	if err != nil {
		w = os.Stdout
	} else {
		w = io.MultiWriter(os.Stdout, logFile)
	}

	Default = slog.New(slog.NewJSONHandler(w, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	})).With("service", serviceName)

	slog.SetDefault(Default)
}
