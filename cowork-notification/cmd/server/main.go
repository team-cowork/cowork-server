package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	chimiddleware "github.com/go-chi/chi/v5/middleware"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"

	"github.com/cowork/cowork-notification/internal/config"
	tokendomain "github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/cowork/cowork-notification/internal/health"
	"github.com/cowork/cowork-notification/internal/infra/fcm"
	kafkainfra "github.com/cowork/cowork-notification/internal/infra/kafka"
	mysqlinfra "github.com/cowork/cowork-notification/internal/infra/mysql"
	"github.com/cowork/cowork-notification/internal/infra/preference"
	"github.com/cowork/cowork-notification/internal/middleware"
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
	if err := db.AutoMigrate(&tokendomain.DeviceToken{}); err != nil {
		slog.Error("auto migrate failed", "err", err)
		os.Exit(1)
	}

	ctx := context.Background()
	fcmSender, err := fcm.NewSender(ctx, cfg.FCMCredentialsFile)
	if err != nil {
		slog.Error("fcm init failed", "err", err)
		os.Exit(1)
	}

	repo := mysqlinfra.NewTokenRepository(db)
	prefClient := preference.NewClient(cfg.PreferenceServiceURL)
	svc := tokendomain.NewService(repo, fcmSender, prefClient)
	handler := tokendomain.NewHandler(svc)

	consumer := kafkainfra.NewConsumer(cfg.KafkaBrokers, cfg.KafkaTopicNotify, cfg.KafkaGroupID, svc)

	eurekaClient := eureka.New(cfg)
	if err := eurekaClient.Register(cfg); err != nil {
		slog.Warn("eureka registration failed", "err", err)
	} else {
		eurekaClient.StartHeartbeat(cfg)
	}

	r := chi.NewRouter()
	r.Use(chimiddleware.RequestID)
	r.Use(chimiddleware.Recoverer)
	r.Get("/health", health.Handler)

	r.Group(func(r chi.Router) {
		r.Use(middleware.ExtractAuthUser)
		r.Post("/notifications/tokens", handler.RegisterToken)
		r.Delete("/notifications/tokens/{token}", handler.DeleteToken)
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
	go func() {
		slog.Info("kafka consumer starting")
		consumer.Start(consumerCtx)
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
	if err := consumer.Close(); err != nil {
		slog.Error("kafka consumer close error", "err", err)
	}
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("server shutdown error", "err", err)
	}
	if err := eurekaClient.Deregister(cfg); err != nil {
		slog.Warn("eureka deregister failed", "err", err)
	}

	slog.Info("shutdown complete")
	if exitCode != 0 {
		os.Exit(exitCode)
	}
}
