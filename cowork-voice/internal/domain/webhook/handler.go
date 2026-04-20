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

func (h *Handler) Handle(w http.ResponseWriter, r *http.Request) {
	event, err := lkwebhook.ReceiveWebhookEvent(r, h.authProvider)
	if err != nil {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	ctx := r.Context()
	h.svc.HandleEvent(ctx, event)

	w.WriteHeader(http.StatusOK)
}
