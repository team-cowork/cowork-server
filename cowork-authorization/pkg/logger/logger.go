package logger

import (
	"io"
	"log/slog"
	"os"
	"strings"
)

var Default *slog.Logger

// moduleFromFunction turns a runtime function name such as
// "github.com/cowork/authorization/internal/domain/auth.(*Service).Login" into its package name
// ("auth"), so Loki can group logs by subsystem without the full function path.
func moduleFromFunction(function string) string {
	rest := function
	if slash := strings.LastIndex(function, "/"); slash >= 0 {
		rest = function[slash+1:]
	}
	if dot := strings.Index(rest, "."); dot >= 0 {
		return rest[:dot]
	}
	return rest
}

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
		Level:     slog.LevelInfo,
		AddSource: true,
		ReplaceAttr: func(_ []string, a slog.Attr) slog.Attr {
			switch a.Key {
			case slog.TimeKey:
				return slog.Attr{Key: "@timestamp", Value: a.Value}
			case slog.SourceKey:
				source, _ := a.Value.Any().(*slog.Source)
				if source == nil {
					return slog.Attr{}
				}
				return slog.Attr{Key: "module", Value: slog.StringValue(moduleFromFunction(source.Function))}
			case slog.MessageKey:
				return slog.Attr{Key: "message", Value: a.Value}
			default:
				return a
			}
		},
	})).With("service", serviceName)

	slog.SetDefault(Default)
}
