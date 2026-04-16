package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/cowork/authorization/internal/client"
	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/handler"
	"github.com/cowork/authorization/internal/repository"
	"github.com/cowork/authorization/internal/service"
	eurekaclient "github.com/cowork/authorization/pkg/eureka"
	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
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

	refreshTokenRepo := repository.NewRefreshTokenRepository(db)

	userClient := client.NewUserClient(cfg.UserServiceURL)
	tokenSvc := service.NewTokenService(cfg)
	authSvc := service.NewAuthService(cfg, userClient, refreshTokenRepo, tokenSvc)

	authHandler := handler.NewAuthHandler(authSvc, tokenSvc)

	router := gin.Default()

	router.GET("/health", handler.Health)

	auth := router.Group("/auth")
	{
		auth.GET("/login", authHandler.Login)
		auth.GET("/callback", authHandler.Callback)
		auth.POST("/refresh", authHandler.Refresh)
		auth.POST("/logout", authHandler.AuthMiddleware(), authHandler.Logout)
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

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("shutting down server...")
	eurekaClient.Deregister(cfg)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("server forced to shutdown: %v", err)
	}
	log.Println("server exited")
}
