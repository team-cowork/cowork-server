package webhook

import (
	"net/http"

	"github.com/livekit/protocol/auth"
	lkwebhook "github.com/livekit/protocol/webhook"

	"github.com/cowork/cowork-voice/internal/apperr"
	"github.com/cowork/cowork-voice/internal/config"
	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

type Handler struct {
	authProvider auth.KeyProvider
	svc          *WebhookService
}

func NewHandler(repo roomdomain.Repository, kafka EventPublisher, cfg *config.AppConfig) *Handler {
	return &Handler{
		authProvider: auth.NewSimpleKeyProvider(cfg.LiveKitAPIKey, cfg.LiveKitAPISecret),
		svc:          NewWebhookService(repo, kafka),
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
