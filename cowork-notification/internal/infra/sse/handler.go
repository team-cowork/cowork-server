package sse

import (
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/cowork/cowork-notification/internal/middleware"
)

const keepaliveInterval = 30 * time.Second

// streamHandler godoc
//
//	@Summary		SSE 알림 스트림 구독
//	@Description	인증된 사용자의 실시간 알림 이벤트를 Server-Sent Events(SSE)로 스트리밍합니다. 30초마다 keepalive ping을 전송합니다.
//	@Tags			notifications
//	@Produce		text/event-stream
//	@Param			X-User-Id	header	int64	true	"사용자 ID (Gateway 주입)"
//	@Success		200	{string}	string	"data: {type, title, body, channelId, teamId}"
//	@Failure		401	{string}	string	"인증 실패"
//	@Failure		500	{string}	string	"SSE not supported"
//	@Router			/notifications/stream [get]
func streamHandler(w http.ResponseWriter, r *http.Request) {} //nolint:unused

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
