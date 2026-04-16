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
	lksdk "github.com/livekit/server-sdk-go/v2"
	"go.mongodb.org/mongo-driver/v2/mongo"
	mongoopts "go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/cowork/cowork-voice/internal/config"
	sessiondomain "github.com/cowork/cowork-voice/internal/domain/session"
	webhookdomain "github.com/cowork/cowork-voice/internal/domain/webhook"
	"github.com/cowork/cowork-voice/internal/health"
	"github.com/cowork/cowork-voice/internal/infra/channel"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
	"github.com/cowork/cowork-voice/internal/middleware"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	cfg, err := config.Load()
	if err != nil {
		slog.Error("config load failed", "err", err)
		os.Exit(1)
	}

	mongoClient, err := mongo.Connect(mongoopts.Client().ApplyURI(cfg.MongoDBURI))
	if err != nil {
		slog.Error("mongodb connect failed", "err", err)
		os.Exit(1)
	}
	if err := mongoClient.Ping(context.Background(), nil); err != nil {
		slog.Error("mongodb ping failed", "err", err)
		os.Exit(1)
	}
	db := mongoClient.Database(cfg.MongoDBDB)

	indexCtx, indexCancel := context.WithTimeout(context.Background(), 15*time.Second)
	if err := sessiondomain.CreateIndexes(indexCtx, db); err != nil {
		indexCancel()
		slog.Error("mongodb index creation failed", "err", err)
		os.Exit(1)
	}
	indexCancel()

	kafkaProducer := kafkadomain.NewProducer(
		cfg.KafkaBrokers,
		cfg.KafkaTopicVoiceEvent,
		cfg.KafkaMessageTimeoutMs,
	)

	livekitClient := lksdk.NewRoomServiceClient(
		cfg.LiveKitURL,
		cfg.LiveKitAPIKey,
		cfg.LiveKitAPISecret,
	)

	channelClient := channel.NewClient(cfg.ChannelServiceURL)
	sessionHandler := sessiondomain.NewHandler(db, channelClient, livekitClient, cfg)
	webhookHandler := webhookdomain.NewHandler(db, kafkaProducer, cfg)

	r := chi.NewRouter()
	r.Use(chimiddleware.RequestID)
	r.Use(chimiddleware.Recoverer)

	r.Get("/health", health.Handler)
	r.Post("/voice/webhook", webhookHandler.Handle)

	r.Group(func(r chi.Router) {
		r.Use(middleware.ExtractAuthUser)
		r.Post("/voice/channels/{channel_id}/join", sessionHandler.Join)
		r.Post("/voice/channels/{channel_id}/leave", sessionHandler.Leave)
		r.Get("/voice/channels/{channel_id}/participants", sessionHandler.Participants)
		r.Get("/voice/sessions/{session_id}", sessionHandler.GetSession)
	})

	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: r,
	}

	done := make(chan os.Signal, 1)
	serverErrCh := make(chan error, 1)
	signal.Notify(done, syscall.SIGINT, syscall.SIGTERM)
	exitCode := 0

	go func() {
		slog.Info("cowork-voice starting", "port", cfg.Port)
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

	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("server shutdown error", "err", err)
	}
	if err := kafkaProducer.Close(); err != nil {
		slog.Error("kafka producer close error", "err", err)
	}
	if err := mongoClient.Disconnect(shutdownCtx); err != nil {
		slog.Error("mongodb disconnect error", "err", err)
	}

	slog.Info("shutdown complete")
	if exitCode != 0 {
		os.Exit(exitCode)
	}
}
