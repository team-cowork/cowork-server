package live

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"

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
// @Description  채널에서 라이브 방송을 시작합니다. 호출자가 호스트가 되며 publish 권한(마이크·화면공유) 토큰을 받습니다. 채널당 동시 라이브는 1개입니다.
// @Tags         live
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  StartResponse
// @Failure      401  {object}  apperr.Error
// @Failure      403  {object}  apperr.Error  "채널 멤버가 아님"
// @Failure      409  {object}  apperr.Error  "이미 라이브 진행 중"
// @Failure      500  {object}  apperr.Error
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
// @Description  진행 중인 라이브에 시청자로 참여합니다. subscribe 전용 토큰을 받습니다. 호스트가 재입장하면 publish 권한 토큰이 재발급됩니다.
// @Tags         live
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  JoinResponse
// @Failure      401  {object}  apperr.Error
// @Failure      403  {object}  apperr.Error  "채널 멤버가 아님"
// @Failure      404  {object}  apperr.Error  "진행 중인 라이브 없음"
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
// @Description  라이브에서 퇴장합니다. 호스트가 퇴장하면 방송 전체가 종료되고 시청자 전원이 퇴장 처리됩니다.
// @Tags         live
// @Security     BearerAuth
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      204  "퇴장 성공"
// @Failure      401  {object}  apperr.Error
// @Failure      404  {object}  apperr.Error  "진행 중인 라이브 없음"
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
// @Success      200  {object}  StatusResponse
// @Failure      401  {object}  apperr.Error
// @Failure      403  {object}  apperr.Error
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
