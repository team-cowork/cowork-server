package webhook

import (
	"net/http"

	"github.com/livekit/protocol/auth"
	lkwebhook "github.com/livekit/protocol/webhook"

	"github.com/cowork/cowork-voice/internal/apperr"
)

type Handler struct {
	authProvider auth.KeyProvider
	svc          *WebhookService
}

func NewHandler(svc *WebhookService, authProvider auth.KeyProvider) *Handler {
	return &Handler{
		authProvider: authProvider,
		svc:          svc,
	}
}

// Handle godoc
// @Summary      LiveKit 웹훅 수신
// @Description  LiveKit 서버가 전송하는 participant_joined / participant_left / room_finished 이벤트를 처리합니다. LiveKit 서버 내부 호출용이며 직접 호출하지 않습니다.
// @Tags         webhook
// @Accept       json
// @Param        Authorization  header  string  true  "LiveKit 웹훅 서명"
// @Success      200
// @Failure      401
// @Router       /voice/webhook [post]
func (h *Handler) Handle(w http.ResponseWriter, r *http.Request) {
	event, err := lkwebhook.ReceiveWebhookEvent(r, h.authProvider)
	if err != nil {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	ctx := r.Context()
	if err := h.svc.HandleEvent(ctx, event); err != nil {
		apperr.WriteResponse(w, apperr.Internal(err.Error()))
		return
	}

	w.WriteHeader(http.StatusOK)
}
