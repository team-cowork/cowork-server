package sse

import (
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/cowork/cowork-notification/internal/middleware"
)

const keepaliveInterval = 30 * time.Second

// Handler는 GET /notifications/stream SSE 엔드포인트를 처리합니다.
func Handler(hub *Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "SSE not supported", http.StatusInternalServerError)
			return
		}

		userID, ok := middleware.AccountIDFromContext(r.Context())
		if !ok {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")
		w.Header().Set("X-Accel-Buffering", "no") // nginx 버퍼링 비활성화

		ch, unsubscribe := hub.Subscribe(userID)
		defer unsubscribe()

		slog.Info("SSE 클라이언트 연결", "userID", userID)

		// 연결 확인용 초기 이벤트
		fmt.Fprintf(w, "event: connected\ndata: {}\n\n")
		flusher.Flush()

		ticker := time.NewTicker(keepaliveInterval)
		defer ticker.Stop()

		for {
			select {
			case payload, open := <-ch:
				if !open {
					return
				}
				fmt.Fprintf(w, "data: %s\n\n", payload)
				flusher.Flush()

			case <-ticker.C:
				// keepalive: 연결 유지용 주석 이벤트
				fmt.Fprintf(w, ": ping\n\n")
				flusher.Flush()

			case <-r.Context().Done():
				slog.Info("SSE 클라이언트 연결 종료", "userID", userID)
				return
			}
		}
	}
}
