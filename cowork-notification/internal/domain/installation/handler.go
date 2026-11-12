package installation

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"

	_ "github.com/cowork/cowork-notification/internal/apidoc" // Gateway response schemas for swag.
	"github.com/cowork/cowork-notification/internal/apperr"
	"github.com/cowork/cowork-notification/internal/middleware"
)

type Handler struct {
	svc InstallationService
}

func NewHandler(svc InstallationService) *Handler {
	return &Handler{svc: svc}
}

type registerInstallationRequest struct {
	FID      string `json:"fid" example:"cXk9Y2Rl-ZWZnaGlqa2xt_"`
	Platform string `json:"platform" enums:"IOS,ANDROID,WEB"`
}

// RegisterInstallation godoc
//
//	@Summary		Firebase 설치 등록
//	@Produce		json
//	@Description	Firebase 설치 ID(FID)를 현재 계정에 등록합니다. 다른 계정에 등록된 FID는 현재 계정으로 이전합니다.
//	@Tags			installations
//	@Accept			json
//	@Param			request		body	registerInstallationRequest	true	"설치 등록 요청"
//	@Success		201  "응답 본문 없음"
//	@Failure		400	{object}	apidoc.Error	"잘못된 요청"
//	@Failure		401	{object}	apidoc.Error	"인증 실패"
//	@Failure		500	{object}	apidoc.Error	"내부 서버 오류"
//	@Security		BearerAuth
//	@Router			/notifications/installations [post]
func (h *Handler) RegisterInstallation(w http.ResponseWriter, r *http.Request) {
	accountID, ok := middleware.AccountIDFromContext(r.Context())
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var req registerInstallationRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.FID == "" || req.Platform == "" {
		http.Error(w, "fid and platform are required", http.StatusBadRequest)
		return
	}
	if err := h.svc.RegisterInstallation(r.Context(), accountID, req.FID, req.Platform); err != nil {
		writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

// DeleteInstallation godoc
//
//	@Summary		Firebase 설치 해제
//	@Produce		json
//	@Description	로그아웃, 계정 전환 또는 FID 변경 시 현재 계정과 FID의 연결을 해제합니다.
//	@Tags			installations
//	@Param			fid			path	string	true	"Firebase installation ID"
//	@Success		204  "응답 본문 없음"
//	@Failure		400	{object}	apidoc.Error	"잘못된 요청"
//	@Failure		401	{object}	apidoc.Error	"인증 실패"
//	@Failure		404	{object}	apidoc.Error	"설치 없음"
//	@Failure		500	{object}	apidoc.Error	"내부 서버 오류"
//	@Security		BearerAuth
//	@Router			/notifications/installations/{fid} [delete]
func (h *Handler) DeleteInstallation(w http.ResponseWriter, r *http.Request) {
	accountID, ok := middleware.AccountIDFromContext(r.Context())
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	fid := chi.URLParam(r, "fid")
	if fid == "" {
		http.Error(w, "fid is required", http.StatusBadRequest)
		return
	}
	if err := h.svc.DeleteInstallation(r.Context(), accountID, fid); err != nil {
		writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func writeError(w http.ResponseWriter, err error) {
	if appErr, ok := err.(*apperr.AppError); ok {
		http.Error(w, appErr.Message, appErr.Code)
		return
	}
	http.Error(w, "internal server error", http.StatusInternalServerError)
}
