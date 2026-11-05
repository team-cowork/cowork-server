package handler

import (
	"errors"
	"log"
	"net/http"
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

// Token godoc
// @Summary      인가 코드로 토큰 발급
// @Description  DataGSM 인가 코드와 PKCE 검증 값으로 액세스 토큰과 리프레시 토큰을 발급합니다.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Param        body  body      object{code=string,code_verifier=string,redirect_uri=string}  true  "코드 교환 요청"
// @Success      200   {object}  apidoc.Success[service.TokenPair]  "새 토큰 쌍"
// @Header       200   {string}  Set-Cookie  "WebSocket 인증 쿠키(cowork_ws_token, Path=/ws)"
// @Failure      400   {object}  apidoc.Error  "필수 요청 값이 없거나 형식이 잘못됨"
// @Failure      401   {object}  apidoc.Error  "인가 코드 검증 실패"
// @Failure      503   {object}  apidoc.Error  "인증 서비스를 일시적으로 사용할 수 없음"
// @Failure      429   {object}  apidoc.Error  "요청 한도 초과"
// @Router       /auth/token [post]
func (h *AuthHandler) Token(c *gin.Context) {
	var req struct {
		Code         string `json:"code"          binding:"required"`
		CodeVerifier string `json:"code_verifier" binding:"required"`
		RedirectURI  string `json:"redirect_uri"  binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "missing required fields"})
		return
	}

	pair, err := h.authSvc.ExchangeCode(c.Request.Context(), req.Code, req.CodeVerifier, req.RedirectURI)
	if err != nil {
		// Provider/DB errors can contain identity or unique-index values.
		log.Printf("token exchange failed")
		if errors.Is(err, service.ErrAuthenticationUnavailable) {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
			return
		}
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication failed"})
		return
	}

	setWsCookie(c, pair)
	c.JSON(http.StatusOK, pair)
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
