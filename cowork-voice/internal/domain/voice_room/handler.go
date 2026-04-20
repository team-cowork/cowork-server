package room

import (
	"encoding/json"
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

// Join godoc
// @Summary      음성 채널 입장
// @Description  채널에 입장합니다. LiveKit 토큰과 연결 URL을 반환합니다. 기존 세션이 없으면 새로 생성됩니다.
// @Tags         voice
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  JoinResponse
// @Failure      401  {object}  apperr.Error
// @Failure      403  {object}  apperr.Error  "채널 멤버가 아님"
// @Failure      500  {object}  apperr.Error
// @Router       /voice/channels/{channel_id}/join [post]
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
// @Summary      음성 채널 퇴장
// @Description  채널에서 퇴장합니다. 마지막 참여자가 나가면 방이 자동으로 삭제됩니다.
// @Tags         voice
// @Security     BearerAuth
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      204  "퇴장 성공"
// @Failure      401  {object}  apperr.Error
// @Failure      404  {object}  apperr.Error  "활성 세션 없음"
// @Router       /voice/channels/{channel_id}/leave [post]
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

// Participants godoc
// @Summary      채널 참여자 목록 조회
// @Description  현재 음성 채널에 참여 중인 사용자 목록을 반환합니다.
// @Tags         voice
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  ParticipantsResponse
// @Failure      401  {object}  apperr.Error
// @Failure      403  {object}  apperr.Error
// @Router       /voice/channels/{channel_id}/participants [get]
func (h *Handler) Participants(w http.ResponseWriter, r *http.Request) {
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

	resp, err := h.svc.GetParticipants(ctx, channelID, userID)
	if err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

// GetSession godoc
// @Summary      세션 조회
// @Description  session_id로 음성 세션 정보를 조회합니다.
// @Tags         voice
// @Security     BearerAuth
// @Produce      json
// @Param        session_id  path      string  true  "세션 ID"
// @Success      200  {object}  SessionResponse
// @Failure      401  {object}  apperr.Error
// @Failure      404  {object}  apperr.Error
// @Router       /voice/sessions/{session_id} [get]
func (h *Handler) GetSession(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	sessionID := chi.URLParam(r, "session_id")

	resp, err := h.svc.GetSession(ctx, sessionID, userID)
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
	json.NewEncoder(w).Encode(v)
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
