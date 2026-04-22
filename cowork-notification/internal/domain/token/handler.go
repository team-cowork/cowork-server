package token

import (
	"encoding/json"
	"net/http"

	"github.com/cowork/cowork-notification/internal/apperr"
	"github.com/cowork/cowork-notification/internal/middleware"
	"github.com/go-chi/chi/v5"
)

type Handler struct {
	svc TokenService
}

func NewHandler(svc TokenService) *Handler {
	return &Handler{svc: svc}
}

type registerTokenRequest struct {
	Token    string `json:"token"`
	Platform string `json:"platform"`
}

// RegisterToken godoc
//
//	@Summary		FCM 토큰 등록
//	@Description	사용자의 디바이스 FCM 토큰을 등록합니다
//	@Tags			tokens
//	@Accept			json
//	@Param			X-User-Id	header	int64					true	"사용자 ID (Gateway 주입)"
//	@Param			request		body	registerTokenRequest	true	"토큰 등록 요청"
//	@Success		201
//	@Failure		400	{string}	string	"잘못된 요청"
//	@Failure		401	{string}	string	"인증 실패"
//	@Failure		500	{string}	string	"내부 서버 오류"
//	@Router			/notifications/tokens [post]
func (h *Handler) RegisterToken(w http.ResponseWriter, r *http.Request) {
	accountID, ok := middleware.AccountIDFromContext(r.Context())
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var req registerTokenRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.Token == "" || req.Platform == "" {
		http.Error(w, "token and platform are required", http.StatusBadRequest)
		return
	}
	switch req.Platform {
	case "ANDROID", "IOS", "WEB":
		// valid
	default:
		http.Error(w, "platform must be ANDROID, IOS, or WEB", http.StatusBadRequest)
		return
	}
	if err := h.svc.RegisterToken(r.Context(), accountID, req.Token, req.Platform); err != nil {
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

// DeleteToken godoc
//
//	@Summary		FCM 토큰 삭제
//	@Description	사용자의 디바이스 FCM 토큰을 삭제합니다
//	@Tags			tokens
//	@Param			X-User-Id	header	int64	true	"사용자 ID (Gateway 주입)"
//	@Param			token		path	string	true	"FCM 토큰"
//	@Success		204
//	@Failure		400	{string}	string	"잘못된 요청"
//	@Failure		401	{string}	string	"인증 실패"
//	@Failure		404	{string}	string	"토큰 없음"
//	@Failure		500	{string}	string	"내부 서버 오류"
//	@Router			/notifications/tokens/{token} [delete]
func (h *Handler) DeleteToken(w http.ResponseWriter, r *http.Request) {
	accountID, ok := middleware.AccountIDFromContext(r.Context())
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	tkn := chi.URLParam(r, "token")
	if tkn == "" {
		http.Error(w, "token is required", http.StatusBadRequest)
		return
	}
	if err := h.svc.DeleteToken(r.Context(), accountID, tkn); err != nil {
		if appErr, ok := err.(*apperr.AppError); ok {
			http.Error(w, appErr.Message, appErr.Code)
			return
		}
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
