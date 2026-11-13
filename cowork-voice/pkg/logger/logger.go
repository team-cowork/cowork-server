package logger

import (
	"log/slog"
	"os"
	"strings"
)

var Default *slog.Logger

// moduleFromFunction turns a runtime function name such as
// "github.com/cowork/cowork-voice/internal/domain/voiceroom.(*Service).Join" into its package name
// ("voiceroom"), so Loki can group logs by subsystem without the full function path.
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
	Default = slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
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
