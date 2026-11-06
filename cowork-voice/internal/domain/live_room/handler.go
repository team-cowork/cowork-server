package live

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"

	_ "github.com/cowork/cowork-voice/internal/apidoc" // Gateway response schemas for swag.
	"github.com/cowork/cowork-voice/internal/apperr"
	"github.com/cowork/cowork-voice/internal/middleware"
)

type Handler struct {
	svc Service
}

func NewHandler(svc Service) *Handler {
	return &Handler{svc: svc}
}

// Start godoc
// @Summary      라이브 방송 시작
// @Description  live_broadcast 권한이 있는 사용자가 호스트가 됩니다. 채널마다 한 번에 하나의 방송만 열 수 있습니다. 마이크에는 voice_speak 권한이 필요하고 카메라와 화면 공유에는 screen_share 권한이 필요합니다.
// @Tags         live
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  apidoc.Success[StartResponse]  "호스트 접속 토큰과 세션 정보"
// @Failure      400  {object}  apidoc.Error  "채널 ID 형식 오류"
// @Failure      401  {object}  apidoc.Error  "인증 필요"
// @Failure      403  {object}  apidoc.Error  "음성 채널 접근 또는 방송 권한 없음"
// @Failure      409  {object}  apidoc.Error  "이미 라이브 진행 중"
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Failure      503  {object}  apidoc.Error  "채널 권한 동기화 중"
// @Router       /live/channels/{channel_id}/start [post]
func (h *Handler) Start(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperr.WriteResponse(w, apperr.BadRequest("invalid channel_id"))
		return
	}

	resp, err := h.svc.Start(ctx, channelID, userID)
	if err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

// Join godoc
// @Summary      라이브 시청 참여
// @Description  시청자는 방송을 수신하며 screen_share 권한이 있으면 카메라도 켤 수 있습니다. 시청자는 마이크와 화면을 공유할 수 없습니다. 호스트가 다시 입장하면 호스트 권한으로 토큰을 발급합니다.
// @Tags         live
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  apidoc.Success[JoinResponse]  "접속 토큰과 세션 정보"
// @Failure      400  {object}  apidoc.Error  "채널 ID 형식 오류"
// @Failure      401  {object}  apidoc.Error  "인증 필요"
// @Failure      403  {object}  apidoc.Error  "음성 채널 접근 권한 없음"
// @Failure      404  {object}  apidoc.Error  "진행 중인 라이브 없음"
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Failure      503  {object}  apidoc.Error  "채널 권한 동기화 중"
// @Router       /live/channels/{channel_id}/join [post]
func (h *Handler) Join(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperr.WriteResponse(w, apperr.BadRequest("invalid channel_id"))
		return
	}

	resp, err := h.svc.Join(ctx, channelID, userID)
	if err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

// Leave godoc
// @Summary      라이브 퇴장
// @Description  라이브에서 퇴장합니다. 호스트가 퇴장하면 방송 전체가 종료되고 시청자도 모두 퇴장합니다.
// @Tags         live
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      204  "퇴장 완료. 응답 본문은 없습니다."
// @Failure      400  {object}  apidoc.Error  "채널 ID 형식 오류"
// @Failure      401  {object}  apidoc.Error  "인증 필요"
// @Failure      403  {object}  apidoc.Error  "음성 채널 접근 권한 없음"
// @Failure      404  {object}  apidoc.Error  "진행 중인 라이브 없음"
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Failure      503  {object}  apidoc.Error  "채널 권한 동기화 중"
// @Router       /live/channels/{channel_id}/leave [post]
func (h *Handler) Leave(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperr.WriteResponse(w, apperr.BadRequest("invalid channel_id"))
		return
	}

	if err := h.svc.Leave(ctx, channelID, userID); err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// Status godoc
// @Summary      라이브 상태 조회
// @Description  채널의 현재 라이브 진행 여부와 시청자 수를 반환합니다.
// @Tags         live
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  apidoc.Success[StatusResponse]  "방송 상태와 시청자 수"
// @Failure      400  {object}  apidoc.Error  "채널 ID 형식 오류"
// @Failure      401  {object}  apidoc.Error  "인증 필요"
// @Failure      403  {object}  apidoc.Error
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Failure      503  {object}  apidoc.Error  "채널 권한 동기화 중"
// @Router       /live/channels/{channel_id} [get]
func (h *Handler) Status(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperr.WriteResponse(w, apperr.BadRequest("invalid channel_id"))
		return
	}

	resp, err := h.svc.GetStatus(ctx, channelID, userID)
	if err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

func parseChannelIDParam(r *http.Request) (int64, error) {
	return strconv.ParseInt(chi.URLParam(r, "channel_id"), 10, 64)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Error("failed to encode response body", "err", err)
	}
}

func toAppError(err error) *apperr.Error {
	if err == nil {
		return nil
	}
	if appErr, ok := err.(*apperr.Error); ok {
		return appErr
	}
	return apperr.Internal(err.Error())
}
