// @title           Cowork Voice API
// @version         1.0
// @description     음성 채널 서비스 — LiveKit 기반 음성 통화 세션 관리
// @BasePath        /api
// @securityDefinitions.apikey BearerAuth
// @in              header
// @name            Authorization
// @description     "Bearer {access_token}" 형식으로 입력하세요.
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
	"github.com/livekit/protocol/auth"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	httpSwagger "github.com/swaggo/http-swagger"
	"go.mongodb.org/mongo-driver/v2/mongo"
	mongoopts "go.mongodb.org/mongo-driver/v2/mongo/options"

	_ "github.com/cowork/cowork-voice/docs"
	"github.com/cowork/cowork-voice/internal/config"
	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
	webhookdomain "github.com/cowork/cowork-voice/internal/domain/webhook"
	"github.com/cowork/cowork-voice/internal/health"
	"github.com/cowork/cowork-voice/internal/infra/channel"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
	lkinfra "github.com/cowork/cowork-voice/internal/infra/livekit"
	mongoinfra "github.com/cowork/cowork-voice/internal/infra/mongo"
	"github.com/cowork/cowork-voice/internal/middleware"
	"github.com/cowork/cowork-voice/internal/monitoring"
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
	pingCtx, pingCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer pingCancel()
	if err := mongoClient.Ping(pingCtx, nil); err != nil {
		slog.Error("mongodb ping failed", "err", err)
		os.Exit(1)
	}
	db := mongoClient.Database(cfg.MongoDBDB)

	indexCtx, indexCancel := context.WithTimeout(context.Background(), 15*time.Second)
	if err := mongoinfra.CreateIndexes(indexCtx, db); err != nil {
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
	sessionRepo := mongoinfra.NewMongoSessionRepository(db)
	livekitRoom := lkinfra.NewLiveKitRoom(
		livekitClient,
		cfg.LiveKitAPIKey,
		cfg.LiveKitAPISecret,
		cfg.LiveKitTokenTTLSecs,
	)
	roomSvc := roomdomain.NewRoomService(sessionRepo, channelClient, livekitRoom, cfg.LiveKitWsURL)
	roomHandler := roomdomain.NewHandler(roomSvc)
	webhookSvc := webhookdomain.NewWebhookService(sessionRepo, kafkaProducer)
	webhookHandler := webhookdomain.NewHandler(
		webhookSvc,
		auth.NewSimpleKeyProvider(cfg.LiveKitAPIKey, cfg.LiveKitAPISecret),
	)

	r := chi.NewRouter()
	r.Use(chimiddleware.RequestID)
	r.Use(chimiddleware.Recoverer)
	r.Use(monitoring.HTTPInFlightMiddleware)
	r.Use(monitoring.HTTPMetricsMiddleware)

	r.Get("/health", health.Handler)
	r.Handle("/metrics", promhttp.Handler())
	r.Get("/swagger/*", httpSwagger.Handler(
		httpSwagger.URL("/swagger/doc.json"),
	))
	r.Post("/voice/webhook", webhookHandler.Handle)

	r.Group(func(r chi.Router) {
		r.Use(middleware.ExtractAuthUser)
		r.Post("/voice/channels/{channel_id}/join", roomHandler.Join)
		r.Post("/voice/channels/{channel_id}/leave", roomHandler.Leave)
		r.Get("/voice/channels/{channel_id}/participants", roomHandler.Participants)
		r.Get("/voice/sessions/{session_id}", roomHandler.GetSession)
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
