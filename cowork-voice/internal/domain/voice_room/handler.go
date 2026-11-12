package room

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

// Join godoc
// @Summary      음성 채널 입장
// @Description  채널에 입장합니다. LiveKit 토큰과 연결 URL을 반환합니다. 기존 세션이 없으면 새 세션을 만듭니다. 마이크에는 voice_speak 권한이 필요하고 카메라와 화면 공유에는 screen_share 권한이 필요합니다.
// @Tags         voice
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  apidoc.Success[JoinResponse]  "접속 토큰과 세션 정보"
// @Failure      400  {object}  apidoc.Error  "채널 ID 형식 오류"
// @Failure      401  {object}  apidoc.Error  "인증 필요"
// @Failure      403  {object}  apidoc.Error  "음성 채널 접근 권한 없음"
// @Failure      409  {object}  apidoc.Error  "음성 채널 정원 초과"
// @Failure      429  {object}  apidoc.Error  "요청 한도 초과"
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Failure      503  {object}  apidoc.Error  "채널 권한 동기화 중"
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
// @Description  마지막 참여자가 퇴장하면 세션이 종료됩니다.
// @Tags         voice
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      204  "퇴장 완료. 응답 본문은 없습니다."
// @Failure      400  {object}  apidoc.Error  "채널 ID 형식 오류"
// @Failure      401  {object}  apidoc.Error  "인증 필요"
// @Failure      403  {object}  apidoc.Error  "음성 채널 접근 권한 없음"
// @Failure      404  {object}  apidoc.Error  "활성 세션 없음"
// @Failure      429  {object}  apidoc.Error  "요청 한도 초과"
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Failure      503  {object}  apidoc.Error  "채널 권한 동기화 중"
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
// @Tags         voice
// @Security     BearerAuth
// @Produce      json
// @Param        channel_id  path      int  true  "채널 ID"
// @Success      200  {object}  apidoc.Success[ParticipantsResponse]  "현재 참여자 목록"
// @Failure      400  {object}  apidoc.Error  "채널 ID 형식 오류"
// @Failure      401  {object}  apidoc.Error  "인증 필요"
// @Failure      403  {object}  apidoc.Error  "음성 채널 접근 권한 없음"
// @Failure      429  {object}  apidoc.Error  "요청 한도 초과"
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Failure      503  {object}  apidoc.Error  "채널 권한 동기화 중"
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
// @Tags         voice
// @Security     BearerAuth
// @Produce      json
// @Param        session_id  path      string  true  "세션 ID"
// @Success      200  {object}  apidoc.Success[SessionResponse]  "음성 세션 정보"
// @Failure      401  {object}  apidoc.Error  "인증 필요"
// @Failure      403  {object}  apidoc.Error  "음성 채널 접근 권한 없음"
// @Failure      404  {object}  apidoc.Error  "세션 없음"
// @Failure      429  {object}  apidoc.Error  "요청 한도 초과"
// @Failure      500  {object}  apidoc.Error  "서버 오류"
// @Failure      503  {object}  apidoc.Error  "채널 권한 동기화 중"
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
