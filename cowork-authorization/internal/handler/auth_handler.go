package handler

import (
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/cowork/authorization/internal/service"
	"github.com/gin-gonic/gin"
)

const (
	oauthStateCookie = "oauth_state"
	userIDKey        = "userID"
)

type AuthHandler struct {
	authSvc  *service.AuthService
	tokenSvc *service.TokenService
}

func NewAuthHandler(authSvc *service.AuthService, tokenSvc *service.TokenService) *AuthHandler {
	return &AuthHandler{authSvc: authSvc, tokenSvc: tokenSvc}
}

// Login godoc
// @Summary      OAuth 로그인 리다이렉트
// @Description  Google OAuth 로그인 페이지로 리다이렉트합니다. state 쿠키가 자동으로 설정됩니다.
// @Tags         auth
// @Success      302  {string}  string  "OAuth provider로 리다이렉트"
// @Router       /auth/signin [get]
func (h *AuthHandler) Login(c *gin.Context) {
	authURL, state := h.authSvc.GetLoginURL()

	c.SetCookie(oauthStateCookie, state, 300, "/", "", false, true)
	c.Redirect(http.StatusFound, authURL)
}

// Callback godoc
// @Summary      OAuth 콜백 처리
// @Description  OAuth provider가 리다이렉트하는 콜백 엔드포인트입니다. 액세스/리프레시 토큰을 반환합니다.
// @Tags         auth
// @Produce      json
// @Param        code   query  string  true  "OAuth authorization code"
// @Param        state  query  string  true  "CSRF 방지용 state 파라미터"
// @Success      200    {object}  map[string]string  "access_token, refresh_token"
// @Failure      400    {object}  map[string]string  "missing oauth state cookie"
// @Failure      401    {object}  map[string]string  "authentication failed"
// @Router       /auth/callback [get]
func (h *AuthHandler) Callback(c *gin.Context) {
	code := c.Query("code")
	state := c.Query("state")

	cookieState, err := c.Cookie(oauthStateCookie)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "missing oauth state cookie"})
		return
	}
	c.SetCookie(oauthStateCookie, "", -1, "/", "", false, true)

	pair, err := h.authSvc.HandleCallback(c.Request.Context(), code, state, cookieState)
	if err != nil {
		log.Printf("callback error: %v", err)
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication failed"})
		return
	}

	c.JSON(http.StatusOK, pair)
}

// Refresh godoc
// @Summary      액세스 토큰 갱신
// @Description  리프레시 토큰으로 새로운 액세스/리프레시 토큰 쌍을 발급합니다.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Param        body  body      object{refresh_token=string}  true  "리프레시 토큰"
// @Success      200   {object}  map[string]string             "새 access_token, refresh_token"
// @Failure      400   {object}  map[string]string             "refresh_token is required"
// @Failure      401   {object}  map[string]string             "invalid or expired refresh token"
// @Router       /auth/refresh [post]
func (h *AuthHandler) Refresh(c *gin.Context) {
	var req struct {
		RefreshToken string `json:"refresh_token" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "refresh_token is required"})
		return
	}

	pair, err := h.authSvc.RefreshTokens(req.RefreshToken)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, pair)
}

// Logout godoc
// @Summary      로그아웃
// @Description  리프레시 토큰을 무효화합니다. Authorization 헤더(Bearer) 필요.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body  object{refresh_token=string}  true  "리프레시 토큰"
// @Success      204   "로그아웃 성공"
// @Failure      400   {object}  map[string]string  "refresh_token is required"
// @Failure      401   {object}  map[string]string  "unauthorized"
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
	if err := h.authSvc.Logout(userID, req.RefreshToken); err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
		return
	}

	c.Status(http.StatusNoContent)
}

func (h *AuthHandler) AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing or invalid authorization header"})
			return
		}

		tokenStr := strings.TrimPrefix(authHeader, "Bearer ")
		claims, err := h.tokenSvc.ValidateAccessToken(tokenStr)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired token"})
			return
		}

		userID, err := strconv.ParseInt(claims.Subject, 10, 64)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token subject"})
			return
		}

		c.Set(userIDKey, userID)
		c.Set("email", claims.Email)
		c.Set("role", claims.Role)
		c.Next()
	}
}

// Health godoc
// @Summary      헬스체크
// @Tags         health
// @Produce      json
// @Success      200  {object}  map[string]interface{}
// @Router       /health [get]
func Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "UP",
		"timestamp": time.Now().UTC(),
	})
}
