package handler

import (
	"errors"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/cowork/authorization/internal/service"
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

func (h *AuthHandler) DevSignIn(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	if c.GetHeader("X-Cowork-Dev-Signin-Verified") != "true" {
		c.AbortWithStatus(http.StatusForbidden)
		return
	}
	if c.ContentType() != "application/json" {
		c.JSON(http.StatusUnsupportedMediaType, gin.H{"error": "application/json is required"})
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 16<<10)
	var req struct {
		ID       string `json:"id" binding:"required"`
		Password string `json:"password" binding:"required"`
	}
	if c.ShouldBindJSON(&req) != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid signin request"})
		return
	}
	pair, err := h.authSvc.DevSignIn(c.Request.Context(), req.ID, req.Password)
	if err != nil {
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
	// Provider is DATAGSM when omitted.
	Provider string `json:"provider"`
}

type LoginStartResponse struct {
	AuthorizationURL string `json:"authorization_url"`
}

func (h *AuthHandler) Authorize(c *gin.Context) {
	h.startAttempt(c, func(req LoginStartRequest) (string, string, error) {
		return h.authSvc.BeginLogin(c.Request.Context(), req.Provider, req.RedirectURI)
	})
}

// LinkAuthorize starts authentication of a second login method for the user that Gateway authenticated.
func (h *AuthHandler) LinkAuthorize(c *gin.Context) {
	h.startAttempt(c, func(req LoginStartRequest) (string, string, error) {
		return h.authSvc.BeginLink(c.Request.Context(), c.GetInt64(userIDKey), req.Provider, req.RedirectURI)
	})
}

func (h *AuthHandler) startAttempt(c *gin.Context, begin func(LoginStartRequest) (string, string, error)) {
	if !h.allowLoginRequest(c) {
		return
	}
	var req LoginStartRequest
	if c.ShouldBindJSON(&req) != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "redirect_uri is required"})
		return
	}
	authorizationURL, session, err := begin(req)
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

// LinkCallback completes a link attempt. The attempt must belong to the user that Gateway authenticated.
func (h *AuthHandler) LinkCallback(c *gin.Context) {
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
	if err := h.authSvc.CompleteLink(c.Request.Context(), c.GetInt64(userIDKey), session, callback); err != nil {
		log.Printf("login method link failed")
		loginError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
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
	case errors.Is(err, service.ErrIdentityNotLinked):
		c.JSON(http.StatusConflict, gin.H{"error": "login method is not linked to the existing account"})
	case errors.Is(err, service.ErrIdentityAlreadyLinked):
		c.JSON(http.StatusConflict, gin.H{"error": "login method is already linked"})
	case errors.Is(err, service.ErrDataGSMLoginRequired):
		c.JSON(http.StatusConflict, gin.H{"error": "datagsm login required"})
	case errors.Is(err, service.ErrAuthenticationUnavailable):
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
	default:
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication failed"})
	}
}

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

// RequireUserID 함수는 Gateway가 전달한 X-User-Id 헤더를 신뢰한다.
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
