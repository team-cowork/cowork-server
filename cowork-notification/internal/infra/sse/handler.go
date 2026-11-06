package sse

import (
	"fmt"
	"log/slog"
	"net/http"
	"time"

	_ "github.com/cowork/cowork-notification/internal/apidoc" // Gateway response schemas for swag.
	"github.com/cowork/cowork-notification/internal/middleware"
)

const keepaliveInterval = 30 * time.Second

// streamHandler godoc
//
//	@Summary		SSE 알림 스트림 구독
//	@Description	연결되면 connected 이벤트를 보내고 이후 알림을 전송합니다. 연결을 유지하기 위해 30초마다 ping 주석을 보냅니다. 같은 알림을 다시 받을 수 있으므로 eventId로 중복을 확인합니다.
//	@Tags			notifications
//	@Produce		text/event-stream,json
//	@Success		200	{string}	string	"data: {eventId, type, title, body, channelId, teamId}"
//	@Failure		401	{object}	apidoc.Error	"인증 실패"
//	@Failure		500	{object}	apidoc.Error	"서버에서 SSE를 지원하지 않음"
//	@Security		BearerAuth
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
		_, _ = fmt.Fprintf(w, "event: connected\ndata: {}\n\n")
		flusher.Flush()

		ticker := time.NewTicker(keepaliveInterval)
		defer ticker.Stop()

		for {
			select {
			case payload, open := <-ch:
				if !open {
					return
				}
				_, _ = fmt.Fprintf(w, "data: %s\n\n", payload)
				flusher.Flush()

			case <-ticker.C:
				// keepalive: 연결 유지용 주석 이벤트
				_, _ = fmt.Fprintf(w, ": ping\n\n")
				flusher.Flush()

			case <-r.Context().Done():
				slog.Info("SSE 클라이언트 연결 종료", "userID", userID)
				return
			}
		}
	}
}
