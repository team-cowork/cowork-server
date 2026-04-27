package monitoring

import (
	"fmt"
	"net/http"
)

func Handler(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	_, _ = fmt.Fprintln(w, "# HELP cowork_notification_up Whether the notification service is running.")
	_, _ = fmt.Fprintln(w, "# TYPE cowork_notification_up gauge")
	_, _ = fmt.Fprintln(w, "cowork_notification_up 1")
}
