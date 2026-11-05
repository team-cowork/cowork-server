package webhook

import (
	"net/http"

	"github.com/livekit/protocol/auth"
	lkwebhook "github.com/livekit/protocol/webhook"

	_ "github.com/cowork/cowork-voice/internal/apidoc" // Gateway response schemas for swag.
	"github.com/cowork/cowork-voice/internal/apperr"
	livedomain "github.com/cowork/cowork-voice/internal/domain/live_room"
)

type Handler struct {
	authProvider auth.KeyProvider
	svc          *WebhookService
	liveSvc      *LiveWebhookService
}

func NewHandler(svc *WebhookService, liveSvc *LiveWebhookService, authProvider auth.KeyProvider) *Handler {
	return &Handler{
		authProvider: authProvider,
		svc:          svc,
		liveSvc:      liveSvc,
	}
}

// Handle godoc
// @Summary      LiveKit 웹훅 수신
// @Description  LiveKit 서버가 전송하는 participant_joined, participant_left, room_finished 이벤트를 처리합니다. 이 경로는 LiveKit 서버에서 호출합니다.
// @Tags         webhook
// @Accept       json
// @Param        Authorization  header  string  true  "LiveKit 웹훅 서명"
// @Produce      json
// @Success      200  "수신 완료. 응답 본문은 없습니다."
// @Failure      401  {object}  apidoc.Error  "웹훅 서명 검증 실패"
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Router       /voice/webhook [post]
func (h *Handler) Handle(w http.ResponseWriter, r *http.Request) {
	event, err := lkwebhook.ReceiveWebhookEvent(r, h.authProvider)
	if err != nil {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	ctx := r.Context()
	// 룸 이름 접두어로 voice/live 서비스에 디스패치한다. voice 서비스는 자기 파서로
	// 미지의 룸 이름을 무시하므로, 접두어가 어느 쪽도 아니면 no-op으로 200을 반환한다.
	var svcErr error
	if livedomain.IsLiveRoomName(event.GetRoom().GetName()) {
		svcErr = h.liveSvc.HandleEvent(ctx, event)
	} else {
		svcErr = h.svc.HandleEvent(ctx, event)
	}
	if svcErr != nil {
		apperr.WriteResponse(w, apperr.Internal(svcErr.Error()))
		return
	}

	w.WriteHeader(http.StatusOK)
}
