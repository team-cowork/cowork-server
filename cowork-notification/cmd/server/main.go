// @title          cowork-notification API
// @version        20260905.0
// @description    FCM 디바이스 토큰 관리 및 푸시 알림 서비스
// @BasePath       /api/notification
// @securityDefinitions.apikey BearerAuth
// @in             header
// @name           Authorization
// @description    "Bearer {access_token}" 형식으로 Gateway에 전달하세요.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	chimiddleware "github.com/go-chi/chi/v5/middleware"
	httpswagger "github.com/swaggo/http-swagger/v2"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"

	_ "github.com/cowork/cowork-notification/docs"
	"github.com/cowork/cowork-notification/internal/config"
	"github.com/cowork/cowork-notification/internal/domain/projection"
	tokendomain "github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/cowork/cowork-notification/internal/health"
	"github.com/cowork/cowork-notification/internal/infra/fcm"
	kafkainfra "github.com/cowork/cowork-notification/internal/infra/kafka"
	mysqlinfra "github.com/cowork/cowork-notification/internal/infra/mysql"
	sseinfra "github.com/cowork/cowork-notification/internal/infra/sse"
	"github.com/cowork/cowork-notification/internal/middleware"
	"github.com/cowork/cowork-notification/internal/monitoring"
	"github.com/cowork/cowork-notification/pkg/eureka"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	cfg, err := config.Load()
	if err != nil {
		slog.Error("config load failed", "err", err)
		os.Exit(1)
	}

	db, err := gorm.Open(mysql.Open(cfg.DBDSN), &gorm.Config{})
	if err != nil {
		slog.Error("mysql connect failed", "err", err)
		os.Exit(1)
	}
	if err := mysqlinfra.Migrate(context.Background(), db, cfg.DBDSN); err != nil {
		slog.Error("mysql migration failed", "err", err)
		os.Exit(1)
	}

	fcmCtx, fcmCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer fcmCancel()
	fcmSender, err := fcm.NewSender(fcmCtx, cfg.FCMCredentialsFile)
	if err != nil {
		slog.Error("fcm init failed", "err", err)
		os.Exit(1)
	}

	repo := mysqlinfra.NewTokenRepository(db)
	projectionRepo := mysqlinfra.NewProjectionRepository(db)
	projectionService := projection.NewService(projectionRepo)
	svc := tokendomain.NewService(repo, fcmSender, projectionRepo)
	handler := tokendomain.NewHandler(svc)

	sseHub := sseinfra.NewHub()
	projectionReadiness := health.NewReadiness()
	notificationConsumer := kafkainfra.NewConsumer(
		cfg.KafkaBrokers,
		cfg.KafkaTopicNotify,
		cfg.KafkaGroupID,
		svc,
		projectionRepo,
		projectionRepo,
		sseHub,
		projectionReadiness,
	)
	projectionConsumer, err := kafkainfra.NewProjectionConsumer(
		cfg.KafkaBrokers,
		cfg.KafkaProjectionGroupID,
		kafkainfra.ProjectionTopics{
			ChannelNotification: cfg.KafkaTopicChannelNotification,
			UserProfile:         cfg.KafkaTopicUserProfile,
			TeamLifecycle:       cfg.KafkaTopicTeamLifecycle,
		},
		projectionService,
		projectionReadiness,
	)
	if err != nil {
		slog.Error("projection kafka consumer init failed", "err", err)
		os.Exit(1)
	}

	r := chi.NewRouter()
	r.Use(chimiddleware.RequestID)
	r.Use(chimiddleware.Recoverer)
	r.Use(monitoring.HTTPMetricsMiddleware)
	r.Get("/health", health.Handler)
	r.Get("/health/ready", health.ReadyHandler(projectionReadiness))
	r.Get("/metrics", monitoring.Handler)
	r.Get("/swagger/*", httpswagger.WrapHandler)

	r.Group(func(r chi.Router) {
		r.Use(middleware.ExtractAuthUser)
		r.Post("/notifications/tokens", handler.RegisterToken)
		r.Delete("/notifications/tokens/{token}", handler.DeleteToken)
		r.Get("/notifications/stream", sseinfra.Handler(sseHub))
	})

	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: r,
	}

	done := make(chan os.Signal, 1)
	serverErrCh := make(chan error, 1)
	signal.Notify(done, syscall.SIGINT, syscall.SIGTERM)
	exitCode := 0

	consumerCtx, consumerCancel := context.WithCancel(context.Background())
	eurekaClient := eureka.New(cfg)
	var eurekaRegistered atomic.Bool
	go func() {
		if !projectionReadiness.Wait(consumerCtx) {
			return
		}
		for {
			if err := eurekaClient.Register(cfg); err == nil {
				eurekaRegistered.Store(true)
				eurekaClient.StartHeartbeat(cfg)
				return
			} else {
				slog.Warn("eureka registration failed; retrying", "err", err)
			}
			timer := time.NewTimer(time.Second)
			select {
			case <-consumerCtx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
		}
	}()
	go func() {
		slog.Info("notification kafka consumer waiting for projection barrier", "topic", cfg.KafkaTopicNotify)
		notificationConsumer.Start(consumerCtx)
	}()
	go func() {
		slog.Info(
			"projection kafka consumer starting",
			"channelPreferenceTopic", cfg.KafkaTopicChannelNotification,
			"userProfileTopic", cfg.KafkaTopicUserProfile,
			"teamLifecycleTopic", cfg.KafkaTopicTeamLifecycle,
		)
		projectionConsumer.Start(consumerCtx)
	}()

	go func() {
		slog.Info("cowork-notification starting", "port", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			serverErrCh <- err
		}
	}()

	select {
	case sig := <-done:
		slog.Info("shutting down", "signal", sig.String())
	case err := <-serverErrCh:
		slog.Error("server error", "err", err)
		exitCode = 1
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	consumerCancel()
	if err := notificationConsumer.Close(); err != nil {
		slog.Error("notification kafka consumer close error", "err", err)
	}
	if err := projectionConsumer.Close(); err != nil {
		slog.Error("projection kafka consumer close error", "err", err)
	}
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("server shutdown error", "err", err)
	}
	if eurekaRegistered.Load() {
		if err := eurekaClient.Deregister(cfg); err != nil {
			slog.Warn("eureka deregister failed", "err", err)
		}
	}

	slog.Info("shutdown complete")
	if exitCode != 0 {
		os.Exit(exitCode)
	}
}
