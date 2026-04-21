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
	userIDKey = "userID"
)

type AuthHandler struct {
	authSvc  *service.AuthService
	tokenSvc *service.TokenService
}

func NewAuthHandler(authSvc *service.AuthService, tokenSvc *service.TokenService) *AuthHandler {
	return &AuthHandler{authSvc: authSvc, tokenSvc: tokenSvc}
}

// Token godoc
// @Summary      토큰 발급 (PKCE code exchange)
// @Description  프론트엔드가 DataGSM에서 직접 받은 인가 코드와 code_verifier로 액세스/리프레시 토큰을 발급합니다.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Param        body  body      object{code=string,code_verifier=string,redirect_uri=string}  true  "코드 교환 요청"
// @Success      200   {object}  service.TokenPair
// @Failure      400   {object}  map[string]string  "missing required fields"
// @Failure      401   {object}  map[string]string  "authentication failed"
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
		log.Printf("token exchange error: %v", err)
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
