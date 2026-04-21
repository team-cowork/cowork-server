// @title           Cowork Authorization API
// @version         1.0
// @description     인증/인가 서비스 — DataGSM OAuth2 PKCE 로그인, JWT 액세스/리프레시 토큰 발급 및 갱신
// @BasePath        /api
// @securityDefinitions.apikey BearerAuth
// @in              header
// @name            Authorization
// @description     "Bearer {access_token}" 형식으로 입력하세요.
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/cowork/authorization/docs"
	"github.com/cowork/authorization/internal/client"
	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/handler"
	"github.com/cowork/authorization/internal/repository"
	"github.com/cowork/authorization/internal/service"
	eurekaclient "github.com/cowork/authorization/pkg/eureka"
	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
	ginSwagger "github.com/swaggo/gin-swagger"
	swaggerFiles "github.com/swaggo/files"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
)

func main() {
	if err := godotenv.Load(); err != nil {
		log.Println("no .env file found, reading from environment")
	}

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("failed to load config: %v", err)
	}

	db, err := gorm.Open(mysql.Open(cfg.DBDSN), &gorm.Config{})
	if err != nil {
		log.Fatalf("failed to connect to database: %v", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		log.Fatalf("failed to get sql.DB: %v", err)
	}
	sqlDB.SetMaxIdleConns(10)
	sqlDB.SetMaxOpenConns(100)
	sqlDB.SetConnMaxLifetime(time.Hour)
	if err := sqlDB.Ping(); err != nil {
		log.Fatalf("database is not reachable: %v", err)
	}

	refreshTokenRepo := repository.NewRefreshTokenRepository(db)

	userClient := client.NewUserClient(cfg.UserServiceURL)
	tokenSvc := service.NewTokenService(cfg)
	authSvc := service.NewAuthService(cfg, userClient, refreshTokenRepo, tokenSvc)

	authHandler := handler.NewAuthHandler(authSvc, tokenSvc)

	router := gin.Default()

	router.GET("/health", handler.Health)
	router.GET("/swagger/*any", ginSwagger.WrapHandler(swaggerFiles.Handler))

	auth := router.Group("/auth")
	{
		auth.GET("/signin", authHandler.Login)
		auth.POST("/token", authHandler.Token)
		auth.POST("/refresh", authHandler.Refresh)
		auth.POST("/signout", authHandler.AuthMiddleware(), authHandler.Logout)
	}

	eurekaClient := eurekaclient.NewClient(cfg)
	if err := eurekaClient.Register(cfg); err != nil {
		log.Printf("warning: eureka registration failed: %v", err)
	} else {
		eurekaClient.StartHeartbeat(cfg)
	}

	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: router,
	}

	go func() {
		log.Printf("cowork-authorization listening on :%s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	stopCleanup := make(chan struct{})
	go func() {
		ticker := time.NewTicker(time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if err := refreshTokenRepo.DeleteExpired(); err != nil {
					log.Printf("failed to delete expired refresh tokens: %v", err)
				}
			case <-stopCleanup:
				return
			}
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	close(stopCleanup)

	log.Println("shutting down server...")
	eurekaClient.Deregister(cfg)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("server forced to shutdown: %v", err)
	}
	log.Println("server exited")
}
