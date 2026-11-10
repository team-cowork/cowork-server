package handler

import (
	"errors"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"time"

	_ "github.com/cowork/authorization/internal/apidoc" // Gateway response schemas for swag.
	"github.com/cowork/authorization/internal/service"
	"github.com/gin-gonic/gin"
)

const (
	userIDKey = "userID"

	// wsCookieName은 Gateway의 /ws/{module} WebSocket 핸드셰이크 인증에 쓰인다.
	// 브라우저의 WebSocket 핸드셰이크는 커스텀 Authorization 헤더를 실을 수 없어,
	// Gateway가 이 쿠키에서 access token을 꺼내 검증한다(Path=/ws로 노출 범위 최소화).
	// 특정 모듈(chat 등)에 종속되지 않으므로 향후 다른 웹소켓 모듈도 그대로 재사용한다.
	wsCookieName = "cowork_ws_token"
	wsCookiePath = "/ws"
)

type AuthHandler struct {
	authSvc *service.AuthService
}

func NewAuthHandler(authSvc *service.AuthService) *AuthHandler {
	return &AuthHandler{authSvc: authSvc}
}

// setWsCookie는 access token을 웹소켓 전용 httpOnly 쿠키로도 내려준다.
// REST 인증에 쓰는 응답 본문의 access_token과 동일한 값·수명을 그대로 재사용한다.
func setWsCookie(c *gin.Context, pair *service.TokenPair) {
	c.SetSameSite(http.SameSiteNoneMode)
	c.SetCookie(wsCookieName, pair.AccessToken, pair.ExpiresIn, wsCookiePath, "", true, true)
}

func clearWsCookie(c *gin.Context) {
	c.SetSameSite(http.SameSiteNoneMode)
	c.SetCookie(wsCookieName, "", -1, wsCookiePath, "", true, true)
}

// Callback godoc
// @Summary      인가 코드로 토큰 발급
// @Description  로그인 시작 때 받은 쿠키와 콜백의 state, code 또는 error를 전달합니다. 서버가 PKCE와 OIDC를 검증하고 Cowork 토큰을 발급합니다. 로그인 시도는 한 번만 사용할 수 있습니다.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Param        body  body      LoginCallbackRequest  true  "DataGSM 콜백"
// @Success      200   {object}  apidoc.Success[service.TokenPair]  "새 토큰 쌍"
// @Header       200   {string}  Set-Cookie  "WebSocket 인증 쿠키(cowork_ws_token, Path=/ws)"
// @Failure      400   {object}  apidoc.Error  "필수 요청 값이 없거나 형식이 잘못됨"
// @Failure      401   {object}  apidoc.Error  "인가 코드 검증 실패"
// @Failure      503   {object}  apidoc.Error  "인증 서비스를 일시적으로 사용할 수 없음"
// @Failure      429   {object}  apidoc.Error  "요청 한도 초과"
// @Router       /auth/callback [post]
func (h *AuthHandler) Callback(c *gin.Context) {
	if !h.allowLoginRequest(c) {
		return
	}
	var req LoginCallbackRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "missing required fields"})
		return
	}

	session, _ := c.Cookie(loginCookieName(req.State))
	setLoginCookie(c, req.State, "", -1)
	callback := url.Values{"state": {req.State}, "code": {req.Code}, "error": {req.Error}}
	pair, err := h.authSvc.CompleteLogin(c.Request.Context(), session, callback)
	if err != nil {
		// Provider/DB errors can contain identity or unique-index values.
		log.Printf("token exchange failed")
		loginError(c, err)
		return
	}

	setWsCookie(c, pair)
	c.JSON(http.StatusOK, pair)
}

type LoginCallbackRequest struct {
	State string `json:"state" binding:"required"`
	Code  string `json:"code"`
	Error string `json:"error"`
}

type LoginStartRequest struct {
	RedirectURI string `json:"redirect_uri" binding:"required"`
}

type LoginStartResponse struct {
	AuthorizationURL string `json:"authorization_url"`
}

// Authorize godoc
// @Summary DataGSM 로그인 시작
// @Description 등록된 콜백 URL로 로그인 시도를 생성합니다. 반환된 authorization_url로 이동하고 쿠키를 유지한 채 /auth/callback에 콜백을 전달합니다. 시도는 10분 뒤 만료됩니다.
// @Tags auth
// @Accept json
// @Produce json
// @Param body body LoginStartRequest true "등록된 콜백 URL"
// @Success 200 {object} apidoc.Success[LoginStartResponse]
// @Header 200 {string} Set-Cookie "일회성 로그인 세션 쿠키(Secure, HttpOnly)"
// @Failure 400 {object} apidoc.Error "등록되지 않은 콜백 또는 잘못된 요청"
// @Failure 403 {object} apidoc.Error "허용하지 않은 출처"
// @Failure 503 {object} apidoc.Error "로그인 시도 저장 실패"
// @Router /auth/authorize [post]
func (h *AuthHandler) Authorize(c *gin.Context) {
	if !h.allowLoginRequest(c) {
		return
	}
	var req LoginStartRequest
	if c.ShouldBindJSON(&req) != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "redirect_uri is required"})
		return
	}
	authorizationURL, session, err := h.authSvc.BeginLogin(c.Request.Context(), req.RedirectURI)
	if err != nil {
		loginError(c, err)
		return
	}
	location, err := url.Parse(authorizationURL)
	if err != nil || location.Query().Get("state") == "" {
		loginError(c, service.ErrAuthenticationUnavailable)
		return
	}
	setLoginCookie(c, location.Query().Get("state"), session, int(service.LoginAttemptTTL.Seconds()))
	c.JSON(http.StatusOK, LoginStartResponse{AuthorizationURL: authorizationURL})
}

func (h *AuthHandler) allowLoginRequest(c *gin.Context) bool {
	c.Header("Cache-Control", "no-store")
	c.Header("Pragma", "no-cache")
	if c.ContentType() != "application/json" {
		c.JSON(http.StatusUnsupportedMediaType, gin.H{"error": "application/json is required"})
		return false
	}
	if !h.authSvc.AllowsLoginOrigin(c.GetHeader("Origin")) {
		c.JSON(http.StatusForbidden, gin.H{"error": "origin is not allowed"})
		return false
	}
	return true
}

func loginCookieName(state string) string {
	// A separate binding per state prevents concurrent tabs from replacing each other's attempt.
	return "__Host-cowork_oauth_" + service.HashToken(state)
}

func setLoginCookie(c *gin.Context, state, value string, maxAge int) {
	c.SetSameSite(http.SameSiteNoneMode)
	c.SetCookie(loginCookieName(state), value, maxAge, "/", "", true, true)
}

func loginError(c *gin.Context, err error) {
	var limited *service.ProviderRateLimitError
	switch {
	case errors.Is(err, service.ErrInvalidLoginRequest):
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid login request"})
	case errors.As(err, &limited):
		if seconds, parseErr := strconv.ParseUint(limited.RetryAfter, 10, 32); parseErr == nil {
			c.Header("Retry-After", strconv.FormatUint(seconds, 10))
		} else if date, parseErr := http.ParseTime(limited.RetryAfter); parseErr == nil {
			c.Header("Retry-After", date.UTC().Format(http.TimeFormat))
		}
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "authentication rate limit exceeded"})
	case errors.Is(err, service.ErrAuthenticationUnavailable):
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
	default:
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication failed"})
	}
}

// Refresh godoc
// @Summary      액세스 토큰 갱신
// @Description  리프레시 토큰을 교환해 새 액세스 토큰과 새 리프레시 토큰을 받습니다.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Param        body  body      object{refresh_token=string}  true  "리프레시 토큰"
// @Success      200   {object}  apidoc.Success[service.TokenPair]  "새 토큰 쌍"
// @Header       200   {string}  Set-Cookie  "WebSocket 인증 쿠키(cowork_ws_token, Path=/ws)"
// @Failure      400   {object}  apidoc.Error  "필수 요청 값이 없거나 형식이 잘못됨"
// @Failure      401   {object}  apidoc.Error  "유효하지 않거나 만료된 리프레시 토큰"
// @Failure      503   {object}  apidoc.Error  "인증 서비스를 일시적으로 사용할 수 없음"
// @Failure      429   {object}  apidoc.Error  "요청 한도 초과"
// @Router       /auth/refresh [post]
func (h *AuthHandler) Refresh(c *gin.Context) {
	var req struct {
		RefreshToken string `json:"refresh_token" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "refresh_token is required"})
		return
	}

	pair, err := h.authSvc.RefreshTokens(c.Request.Context(), req.RefreshToken)
	if err != nil {
		log.Printf("refresh token rotation failed")
		if errors.Is(err, service.ErrAuthenticationUnavailable) {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
			return
		}
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired refresh token"})
		return
	}

	setWsCookie(c, pair)
	c.JSON(http.StatusOK, pair)
}

// Logout godoc
// @Summary      로그아웃
// @Description  리프레시 토큰을 무효화하고 WebSocket 인증 쿠키를 삭제합니다.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body  object{refresh_token=string}  true  "리프레시 토큰"
// @Success      204   "로그아웃 완료. 응답 본문은 없습니다."
// @Failure      400   {object}  apidoc.Error  "필수 요청 값이 없거나 형식이 잘못됨"
// @Failure      401   {object}  apidoc.Error  "인증 실패 또는 유효하지 않은 리프레시 토큰"
// @Failure      503   {object}  apidoc.Error  "인증 서비스를 일시적으로 사용할 수 없음"
// @Failure      429   {object}  apidoc.Error  "요청 한도 초과"
// @Router       /auth/signout [post]
func (h *AuthHandler) Logout(c *gin.Context) {
	var req struct {
		RefreshToken string `json:"refresh_token" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "refresh_token is required"})
		return
	}

	userID := c.GetInt64(userIDKey)
	if err := h.authSvc.Logout(c.Request.Context(), userID, req.RefreshToken); err != nil {
		log.Printf("refresh token revocation failed")
		if errors.Is(err, service.ErrAuthenticationUnavailable) {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
			return
		}
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid refresh token"})
		return
	}

	clearWsCookie(c)
	c.Status(http.StatusNoContent)
}

// RequireUserID는 Gateway가 전달한 X-User-Id 헤더를 신뢰한다.
// cowork-gateway만 JWT를 검증하므로, 다운스트림 서비스인 이곳에서는 JWT를 직접 파싱/검증하지 않는다.
func RequireUserID() gin.HandlerFunc {
	return func(c *gin.Context) {
		userID, err := strconv.ParseInt(c.GetHeader("X-User-Id"), 10, 64)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing or invalid X-User-Id header"})
			return
		}

		c.Set(userIDKey, userID)
		c.Next()
	}
}

func Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "UP",
		"timestamp": time.Now().UTC(),
	})
}
